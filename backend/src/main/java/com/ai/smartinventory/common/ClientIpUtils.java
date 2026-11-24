package com.ai.smartinventory.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 客户端真实 IP 提取工具
 *
 * 【修复说明】原 AuthController.getClientIp() 直接 return "127.0.0.1"，
 * 导致 sys_user.last_login_ip 字段永远是本地回环地址，形同虚设；
 * 同时若将其用于风控（限流、异常登录识别）会完全失效。
 * RateLimitAspect 里已有一份正确的 X-Forwarded-For 解析逻辑，
 * 现抽取为公共工具，两处共用，避免重复实现与行为不一致。
 */
public final class ClientIpUtils {

    private static final String UNKNOWN = "unknown";

    private ClientIpUtils() {
    }

    /**
     * 从当前请求上下文获取客户端 IP；无上下文时返回 "unknown"。
     */
    public static String getClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return UNKNOWN;
        }
        return getClientIp(attrs.getRequest());
    }

    /**
     * 提取客户端 IP。
     *
     * <p>【M-1 修复】只有当**直连方是可信代理**（回环 / 私有网段）时，才采信其转发的
     * {@code X-Forwarded-For} 等头；否则一律使用 TCP 层的 {@code getRemoteAddr()}。
     *
     * <p>原因：原实现无条件信任 {@code X-Forwarded-For} 并取第一段，于是任意客户端
     * 只要自带 {@code X-Forwarded-For: 1.2.3.4} 就能把自己伪装成一个「新 IP」——
     * 使得「按 IP 限流」（RateLimitAspect 的限流键含 IP）与 {@code last_login_ip}
     * 全部失效，登录接口可被无成本爆破。
     *
     * <p>开发环境经 Vite 代理访问时，直连方是回环地址（可信），行为与修复前一致。
     *
     * <p>X-Forwarded-For 可能是 "客户端IP, 代理1IP, 代理2IP" 的链路，第一个才是真实客户端地址。
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String remote = request.getRemoteAddr();
        String ip = null;
        if (isTrustedProxy(remote)) {
            ip = firstValid(
                    request.getHeader("X-Forwarded-For"),
                    request.getHeader("X-Real-IP"),
                    request.getHeader("Proxy-Client-IP"),
                    request.getHeader("WL-Proxy-Client-IP"),
                    request.getHeader("HTTP_CLIENT_IP"),
                    request.getHeader("HTTP_X_FORWARDED_FOR")
            );
        }
        if (ip == null) {
            ip = remote;
        }
        // 兼容 IPv6 回环地址，统一表示为 127.0.0.1 便于阅读
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            ip = "127.0.0.1";
        }
        return ip == null ? UNKNOWN : ip;
    }

    /**
     * 直连方是否为可信代理：回环地址或私有网段（RFC1918）。
     * 只有这类来源转发的 X-Forwarded-For 才可信。
     */
    private static boolean isTrustedProxy(String addr) {
        if (addr == null) {
            return false;
        }
        if ("127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr)) {
            return true;
        }
        if (addr.startsWith("10.") || addr.startsWith("192.168.") || addr.startsWith("169.254.")) {
            return true;
        }
        // 172.16.0.0/12
        if (addr.startsWith("172.")) {
            int firstDot = addr.indexOf('.');
            int secondDot = addr.indexOf('.', firstDot + 1);
            if (firstDot > 0 && secondDot > firstDot) {
                try {
                    int second = Integer.parseInt(addr.substring(firstDot + 1, secondDot));
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private static String firstValid(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && !UNKNOWN.equalsIgnoreCase(c.trim())) {
                int comma = c.indexOf(',');
                return comma > 0 ? c.substring(0, comma).trim() : c.trim();
            }
        }
        return null;
    }
}
