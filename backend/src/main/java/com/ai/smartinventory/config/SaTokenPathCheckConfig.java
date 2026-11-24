package com.ai.smartinventory.config;

import cn.dev33.satoken.exception.RequestPathInvalidException;
import cn.dev33.satoken.strategy.SaStrategy;
import com.ai.smartinventory.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Sa-Token 请求路径校验的响应修正。
 *
 * 【背景】sa-token-spring-boot3-starter 会自动注册一个全局过滤器
 * SaPathCheckFilterForJakartaServlet，对**所有**请求路径做非法字符校验。
 * 非法字符表（SaTokenConsts/SaStrategy.INVALID_CHARACTER，共 9 项）：
 *   //   \   %2e   %2E   %2f   %2F   %5c   %5C   %25
 * 另外还额外拒绝「含 /. 或含 \.」的路径。
 *
 * 【缺陷】该过滤器默认的分支只设置了 contentType，**从未调用 setStatus()**：
 *     response.setContentType("text/plain; charset=utf-8");
 *     response.getWriter().print(e.getMessage());
 * 于是被拒绝的请求返回的是 **HTTP 200**，响应体是纯文本「非法请求：<路径>」。
 *
 * 这带来两个真实危害：
 *  1. 监控/网关/CDN 看到的是 2xx，认为一切正常；被拒绝的请求不会触发任何告警。
 *  2. 如果这个 URL 指向图片，浏览器拿到的是 200 + text/plain，
 *     <img> 解码失败 —— 表现为**破图而不是 404**，排查方向被彻底带偏。
 *     （实测：/api/uploads/2026/09/20/.hidden 与 .../a%2Eb.jpg 均返回 200 text/plain）
 *
 * 【修复】利用 Sa-Token 提供的官方扩展点 SaStrategy.instance.requestPathInvalidHandle，
 * 把它改写成规范的 400 + JSON，与项目其它错误响应保持一致。
 * 合法请求（路径不含上述字符）完全不受影响。
 */
@Slf4j
@Configuration
public class SaTokenPathCheckConfig {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PostConstruct
    public void init() {
        SaStrategy.instance.requestPathInvalidHandle =
                SaTokenPathCheckConfig::handleInvalidPath;
        log.info("Sa-Token 非法请求路径处理器已接管：将由 200 纯文本改为 400 JSON");
    }

    private static void handleInvalidPath(RequestPathInvalidException e, Object request, Object response) {
        if (!(response instanceof HttpServletResponse res)) {
            log.warn("非法请求路径（响应对象类型无法识别）: {}", e.getMessage());
            return;
        }
        try {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(
                    OBJECT_MAPPER.writeValueAsString(Result.error(400, "请求路径非法")));
            res.getWriter().flush();
        } catch (IOException ioe) {
            log.warn("写出非法路径响应失败: {}", ioe.getMessage());
        } catch (IllegalStateException ise) {
            // 响应已提交时无法再改状态码，记录即可
            log.warn("非法请求路径，但响应已提交，无法改写状态码: {}", e.getMessage());
        }
    }
}
