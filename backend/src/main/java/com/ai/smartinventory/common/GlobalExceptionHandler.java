package com.ai.smartinventory.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * 【修复说明】原文件为空（仅 1 行），导致：
 * 1. 业务异常全部透传为 Spring 默认 500 HTML 错误页，前端 axios 拦截器
 *    拿到的是非 JSON 响应，无法给出友好提示；
 * 2. 参数校验失败返回的是 Spring 的默认结构，前端 message 显示为空白或乱码；
 * 3. Sa-Token 的未登录/无权限异常被上游 Filter 吞掉或转成 500；
 * 4. 限流被拒时只返回裸文本，前端无法识别为 429。
 *
 * 本类统一将各类异常收敛为 Result 结构，HTTP 状态码与业务 code 保持一致。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（主动抛出，携带自定义 code）
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 - {} {} - code={}, msg={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        Integer code = e.getCode() == null ? 500 : e.getCode();
        return Result.error(code, e.getMessage());
    }

    /**
     * Sa-Token 未登录
     */
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLoginException(NotLoginException e, HttpServletRequest request) {
        log.warn("未登录访问 - {} {} - type={}", request.getMethod(), request.getRequestURI(), e.getType());
        String message;
        switch (e.getType()) {
            case NotLoginException.NOT_TOKEN:
                message = "未提供登录凭证，请先登录";
                break;
            case NotLoginException.INVALID_TOKEN:
                message = "登录凭证无效，请重新登录";
                break;
            case NotLoginException.TOKEN_TIMEOUT:
                message = "登录已过期，请重新登录";
                break;
            case NotLoginException.BE_REPLACED:
                message = "账号已在其他设备登录，请重新登录";
                break;
            case NotLoginException.KICK_OUT:
                message = "账号已被强制下线，请重新登录";
                break;
            default:
                message = "未授权，请先登录";
        }
        return Result.unauthorized(message);
    }

    /**
     * Sa-Token 角色不足（@SaCheckRole）
     */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRoleException(NotRoleException e, HttpServletRequest request) {
        log.warn("角色不足 - {} {} - require={}", request.getMethod(), request.getRequestURI(), e.getRole());
        return Result.forbidden("当前角色无权访问该功能");
    }

    /**
     * Sa-Token 权限不足（@SaCheckPermission）
     */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermissionException(NotPermissionException e, HttpServletRequest request) {
        log.warn("权限不足 - {} {} - require={}", request.getMethod(), request.getRequestURI(), e.getPermission());
        return Result.forbidden("当前账号无权执行该操作");
    }

    /**
     * @Valid 校验失败（@RequestBody 对象）
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        if (message.isEmpty()) {
            message = "请求参数校验失败";
        }
        log.warn("参数校验失败 - {}", message);
        return Result.error(400, message);
    }

    /**
     * 表单/查询参数绑定校验失败
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        if (message.isEmpty()) {
            message = "请求参数绑定失败";
        }
        log.warn("参数绑定失败 - {}", message);
        return Result.error(400, message);
    }

    /**
     * 缺少必填请求参数
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数 - {}", e.getParameterName());
        return Result.error(400, "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 请求体不可读（JSON 格式错误等）
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 - {}", e.getMessage());
        return Result.error(400, "请求数据格式错误，请检查后重试");
    }

    /**
     * 路径变量 / 查询参数类型不匹配（如 /after-sale/eligibility/abc 里的 id）。
     *
     * 【为什么补这一条】此前没有该映射，MethodArgumentTypeMismatchException
     * 会一路掉进最底部的 Exception 兜底分支，返回 500「系统繁忙」——
     * 明明是「参数格式不对」（客户端问题），却被报成「服务端故障」，
     * 既误导排查方向，也会让 500 监控被无效告警淹没。
     * 现按语义返回 400，且只回显参数名，不回显原始值（避免反射型注入）。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配 - name={}, value={}", e.getName(), e.getValue());
        return Result.error(400, "参数格式不正确：" + e.getName());
    }

    /**
     * 请求方法不支持
     */
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 - {}", e.getMessage());
        return Result.error(405, "请求方式不被支持：" + e.getMethod());
    }

    /**
     * 404 - 路由不存在
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("接口不存在 - {}", e.getRequestURL());
        return Result.error(404, "请求的接口不存在");
    }

    /**
     * 404 - 静态资源不存在
     *
     * 【修复】Spring 6.1 起，缺失的静态资源抛的是 NoResourceFoundException，
     * 而不是上面那个 NoHandlerFoundException。此前缺这条映射，
     * /uploads/** 下找不到的文件会一路掉进最底部的 Exception 兜底分支，
     * 返回 500「系统繁忙，请稍后重试」——
     * 现象是「图片丢了」被报成「服务器故障」，排查方向直接被带偏。
     * （实测触发场景：上传目录因 ${user.dir} 漂移而分裂，历史图片落在另一个目录。）
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("静态资源不存在 - {} {}", e.getHttpMethod(), e.getResourcePath());
        return Result.error(404, "请求的资源不存在");
    }

    /**
     * 唯一键冲突
     *
     * 【为什么必须单独处理】此前 DuplicateKeyException 会掉进最底部的
     * Exception 兜底分支，对外只返回 500「系统繁忙，请稍后重试」——
     * 真正的冲突原因（哪张表、哪个唯一键）完全看不见，
     * 排查方向被直接带偏（实测：cart 表 uk_user_product(user_id, product_id)
     * 与 @TableLogic 逻辑删除冲突，表现为"删除后再加同一商品永远 500"）。
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        log.warn("唯一键冲突 - {} {} - {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return Result.error(409, "数据重复，该记录已存在");
    }

    /**
     * 兜底：未预期的系统异常。对外隐藏堆栈细节，对内完整记录日志。
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 - {} {}", request.getMethod(), request.getRequestURI(), e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
