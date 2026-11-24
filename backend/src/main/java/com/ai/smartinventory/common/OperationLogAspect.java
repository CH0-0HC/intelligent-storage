package com.ai.smartinventory.common;

import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.service.OperationLogService;
import com.ai.smartinventory.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 操作日志切面 —— 把「写操作」真正落库到 {@code operation_log}（R17）。
 *
 * 【为什么需要这一层】
 * 在 R17 之前，{@code operation_log} 表**零写入者**：
 * 「日志管理」页面是个空壳，管理端看不到任何审计记录。
 * {@code OperationLogService.recordLog(...)} 九参方法早就写好了，只是没人调用。
 *
 * 【设计取舍】
 * 1. 切点只落在 {@code controller} 包 —— 因此不会递归切入
 *    {@code OperationLogServiceImpl} 自身的 save（它在 service 包）。
 * 2. **不过滤注解、而是过滤 HTTP 方法**：进入切面后用
 *    {@code request.getMethod()} 判断是否 POST/PUT/DELETE/PATCH。
 *    若用 {@code @annotation(PostMapping)} 式切点，会漏掉
 *    用 {@code @RequestMapping(method = POST)} 写法的方法。
 * 3. **失败不能影响业务**：整段落库在 try/catch 内，任何异常只打 WARN。
 *    本切面抛出的异常绝不允许冒泡到 controller 调用方。
 * 4. **脱敏是硬要求**：参数序列化后按 key 掩码，避免明文口令/令牌进日志表。
 *
 * 【与全局异常出口的关系】
 * 已被鉴权拦下的请求根本到不了 controller，因此本切面**只记录"进入了业务层"的请求**，
 * 不记录被 401/403 拒绝的尝试（那属于另一个议题，不在 R17 范围）。
 *
 * @see RateLimitAspect 同包的切面范式（@Around + RequestContextHolder 取 IP）
 * @see MerchantContext#currentUserId() 取当前登录用户 id 的既有约定
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    /** 仅这些 HTTP 方法算「写操作」，值得进审计日志 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    /** params 列是 TEXT，但仍截断，避免一个巨型请求体把日志表撑爆 */
    private static final int MAX_PARAMS_LEN = 1000;

    private static final int MAX_ERROR_LEN = 500;

    /** 【P1-5】result 列截断长度：返回体可能很大（如商品列表），只留摘要 */
    private static final int MAX_RESULT_LEN = 1000;

    /** 【P1-5】userAgent 列截断长度（列定义 varchar(500)，留余量） */
    private static final int MAX_USER_AGENT_LEN = 500;

    /**
     * 敏感字段掩码：JSON 中这些 key 的字符串值一律替换为 {@code ***}。
     *
     * 【为什么不靠 @JsonProperty(WRITE_ONLY)】
     * 那个注解只在实体上有效（User.password 已用）。但登录接口的入参可能不是实体，
     * 也可能是 Map，且将来新增 DTO 容易漏。切面兜一层，属于「纵深防御」。
     */
    private static final Pattern SENSITIVE_JSON = Pattern.compile(
            "(\"(?:password|oldPassword|newPassword|confirmPassword|token|secret|apiKey|authorization)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private final OperationLogService operationLogService;
    private final UserService userService;

    /** findAndRegisterModules：自动注册 classpath 上的 jsr310 等模块，避免 LocalDateTime 序列化失败 */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Around("execution(* com.ai.smartinventory.controller..*(..))")
    public Object around(ProceedingJoinPoint jp) throws Throwable {
        HttpServletRequest request = currentRequest();
        // 非 HTTP 上下文（内部调用）或读操作：原样放行，完全不进日志链路
        if (request == null || !WRITE_METHODS.contains(request.getMethod().toUpperCase())) {
            return jp.proceed();
        }

        long start = System.currentTimeMillis();
        Object result = null;
        Throwable thrown = null;
        try {
            result = jp.proceed();
            return result;
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            // 记录动作本身必须「绝不抛异常」——它挂在业务方法的调用栈上
            try {
                writeLog(jp, request, result, thrown, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("操作日志写入失败（已吞掉，不影响业务）：{}", e.getMessage());
            }
        }
    }

    private void writeLog(ProceedingJoinPoint jp, HttpServletRequest request, Object result,
                          Throwable thrown, long costMs) {
        Long userId = MerchantContext.currentUserId();
        // 【测试报告 P3-4】userId 为 null 时（登录失败、注册等**全程无会话**的请求）
        // 从入参里兜底取 username，否则「日志管理」的操作人列是空的 ——
        // 恰恰是最该追溯的两类记录（谁在撞库、谁刚注册）反而是空的。
        String username = resolveUsername(userId);
        if (username == null) {
            username = usernameFromArgs(jp.getArgs());
        }

        String operation = request.getMethod().toUpperCase() + " " + jp.getSignature().getName();
        String method = request.getMethod() + " " + request.getRequestURI();
        String params = mask(truncate(serializeArgs(jp.getArgs()), MAX_PARAMS_LEN));
        // 【P1-5】result：业务返回体摘要（截断），排查时能直接看到「返回了成功还是业务错误」
        String resultSummary = thrown == null
                ? truncate(serializeResult(result), MAX_RESULT_LEN)
                : null;
        // 【P1-5】userAgent：客户端标识，排查「谁用什么端发起」时必需
        String userAgent = truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LEN);
        String ip = ClientIpUtils.getClientIp(request);
        Integer status = thrown == null ? 1 : 0;
        String errorMsg = thrown == null ? null
                : truncate(thrown.getClass().getSimpleName()
                           + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage()),
                           MAX_ERROR_LEN);

        operationLogService.recordLog(userId, username, operation, method,
                params, ip, status, costMs, errorMsg, resultSummary, userAgent);
    }

    /**
     * 【测试报告 P3-4】未登录态下从方法入参里捞操作人。
     *
     * <p><b>为什么会有空操作人。</b>{@link MerchantContext#currentUserId()} 读的是
     * Sa-Token 会话。登录**失败**（口令错、用户不存在）与注册这两类请求从头到尾
     * 没有会话 ⇒ userId = null ⇒ {@link #resolveUsername(Long)} 返回 null。
     * 实测（沙箱真起后端 + MySQL 8.4 打接口）：登录成功写得到 {@code admin}/{@code user}，
     * 登录失败与注册两行 username 均为 NULL。也就是说报告里
     * 「登录发生在鉴权前，切面取不到当前用户」只对了一半 ——
     * 成功登录其实在同一请求内已建立会话，是取得到的。
     *
     * <p><b>为什么放在这里、而不是在 AuthController 里补记。</b>
     * 登录/注册 DTO 本来就有 username，取它零成本；若在登录接口单独写一条，
     * 等于把「谁在操作」拆成两处写入，以后新增未登录端点照样会漏。
     * 放在切面对**所有**未登录写操作统一生效。
     *
     * <p><b>为什么用反射而不是 {@code instanceof}。</b> LoginDTO / RegisterDTO 是两个类，
     * 将来还可能加第三方登录 DTO；逐个 instanceof 每加一个就得改这里。
     * 反射找 {@code getUsername()} 是一次性成本，找不到就返回 null ——
     * 取不到操作人绝不能影响日志写入本身。
     *
     * @param args controller 方法入参（可能为 null）
     * @return 第一个非空的 username，取不到返回 {@code null}
     */
    static String usernameFromArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            try {
                java.lang.reflect.Method getter = arg.getClass().getMethod("getUsername");
                Object value = getter.invoke(arg);
                if (value instanceof String name && !name.isBlank()) {
                    return name;
                }
            } catch (NoSuchMethodException e) {
                // 该入参不是带 username 的 DTO，换下一个
            } catch (Exception e) {
                // 反射失败只影响「操作人」这一列，绝不冒泡到业务调用方
            }
        }
        return null;
    }

    /**
     * 把 controller 返回体序列化为可读文本。
     *
     * 【为什么要脱敏】
     * 登录/改密接口的返回体里可能带新的 token，直接落库等于把凭证写进审计表。
     * 复用与 params 同一套 {@link #SENSITIVE_JSON} 掩码。
     *
     * 【为什么失败要吞掉】
     * result 只是「附加信息」，序列化失败绝不能把整条日志搞没 —— 返回占位符即可。
     */
    private String serializeResult(Object result) {
        if (result == null) {
            return null;
        }
        try {
            return mask(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            return "[无法序列化的返回体：" + e.getClass().getSimpleName() + "]";
        }
    }

    /**
     * 取用户名。
     *
     * 【为什么查库而不是读 Session】
     * 登录时只往 Sa-Token Session 写了 {@code userRole} 与 {@code merchantId}，
     * 并没有写 username。而「日志管理」页支持按 username 过滤，
     * 留空会让该过滤条件永远命中不了 —— 所以这里补一次查询。
     * 写操作的频率很低（下单/加购/管理动作），一次主键查询的开销可以接受。
     */
    private String resolveUsername(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            User user = userService.getById(userId);
            return user == null ? null : user.getUsername();
        } catch (Exception e) {
            // 取不到用户名不能拖垮日志写入本身
            return null;
        }
    }

    /**
     * 把方法入参序列化为可读 JSON。
     *
     * 【为什么要过滤 Servlet 对象与文件】
     * 进入 @Around 时 Spring 已完成参数解析，所以 jp.getArgs() 能拿到
     * 已反序列化的 @RequestBody 对象（这是选切面而不是 Filter 的关键理由）。
     * 但入参里也可能混有 request/response（序列化会炸或产生巨量噪音）
     * 以及 MultipartFile（二进制，绝不能进日志表）。
     */
    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        List<Object> clean = new ArrayList<>(args.length);
        for (Object arg : args) {
            if (arg == null
                    || arg instanceof HttpServletRequest
                    || arg instanceof HttpServletResponse
                    || arg instanceof MultipartFile
                    || arg instanceof MultipartFile[]) {
                continue;
            }
            clean.add(arg);
        }
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            return "[无法序列化的参数：" + e.getClass().getSimpleName() + "]";
        }
    }

    private String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return SENSITIVE_JSON.matcher(text).replaceAll("$1\"***\"");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(已截断)";
    }

    private HttpServletRequest currentRequest() {
        // 与 RateLimitAspect 同一手法：切面拿不到方法参数里的 request，只能走 RequestContextHolder
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }
}
