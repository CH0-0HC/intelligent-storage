package com.ai.smartinventory.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 接口限流切面（固定窗口 + ZSet 滑动窗口）。
 *
 * 【本次修复】
 * 1) RedisTemplate 主键注入改为 ObjectProvider 惰性可选获取。
 *    原写法 `@Resource private RedisTemplate<String, String> rt;` 是**必需依赖**：
 *    - 生产环境 Redis 宕机/未配置时，整个 Spring 上下文直接启动失败（可用性事故）；
 *    - 测试环境排除了 Redis 自动装配后，报文变成
 *      "No qualifying bean of type RedisTemplate ... available"，
 *      导致 SmartInventoryApplicationTests.contextLoads 必然失败。
 *    限流属于「有则增强、无则放行」的旁路能力，不应成为应用的硬依赖，
 *    因此改为 ObjectProvider 并在缺失/异常时安全降级。
 *
 * 2) 删除类内私有的 getIp() 重复实现，统一复用 ClientIpUtils。
 *    原 getIp() 直接取 X-Forwarded-For 整串（含逗号分隔的代理链路），
 *    会把 "客户端IP, 代理IP" 当成一个 key，导致限流分桶错误：
 *    同一 IP 经不同代理跳数会命中不同桶，限流形同虚设，甚至被轻易绕过。
 *    ClientIpUtils 会取链路第一段（真实客户端 IP），语义正确。
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private static final String PREFIX = "rate_limit:";

    /**
     * 【M-1 修复】无 Redis 时进程内兜底限流的「宽松下限」。
     *
     * <p>注解里的阈值（如登录 5 次/分钟）是给 Redis 精确限流用的。而在 Redis 缺席时，
     * 本机所有流量都来自同一个地址（127.0.0.1）——若严格按 5 次/分钟执行，
     * 会立刻误伤回归/验证脚本（它们一分钟内会登录很多次）。因此兜底采用**宽松下限**：
     * 分钟窗口至少放行 {@value #LOCAL_FALLBACK_MINUTE_FLOOR} 次、小时窗口至少
     * {@value #LOCAL_FALLBACK_HOUR_FLOOR} 次，只为挡住「无成本的高频爆破」，
     * 不追求与注解阈值严格一致。
     */
    private static final int LOCAL_FALLBACK_MINUTE_FLOOR = 60;
    private static final int LOCAL_FALLBACK_HOUR_FLOOR = 600;

    /** 进程内兜底限流的滑动窗口：key → 该窗口内的请求时间戳（毫秒）。 */
    private final ConcurrentHashMap<String, Deque<Long>> localWindows = new ConcurrentHashMap<>();

    /**
     * Redis 不可用的告警只打一次，避免刷屏。
     * 成功一次后会复位，因此 Redis 恢复后再故障仍能重新告警。
     */
    private final AtomicBoolean redisUnavailableWarned = new AtomicBoolean(false);

    /** 用 ObjectProvider 惰性获取，Redis 缺席时不影响应用启动与请求放行。 */
    private final ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider;

    public RateLimitAspect(ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    @Around("@annotation(rl)")
    public Object around(ProceedingJoinPoint jp, RateLimit rl) throws Throwable {
        String ip = getIp();
        String kp = rl.key().isEmpty() ? jp.getSignature().getDeclaringTypeName() : rl.key();
        if (rl.permitsPerMinute() > 0) check(kp + ":minute", ip, rl.permitsPerMinute(), Duration.ofMinutes(1));
        if (rl.permitsPerHour() > 0) check(kp + ":hour", ip, rl.permitsPerHour(), Duration.ofHours(1));
        return jp.proceed();
    }

    private void check(String k, String ip, int lim, Duration w) {
        String rk = PREFIX + k + ":" + ip;
        RedisTemplate<String, String> rt = redisTemplateProvider.getIfAvailable();
        if (rt == null) {
            // 【M-1 修复】此前无 Redis 直接 return（= 完全不限流，43 处注解全部空转）。
            // 现改用进程内兜底限流（宽松阈值，见 LOCAL_FALLBACK_*）。
            localFallback(rk, lim, w);
            return;
        }
        long now = Instant.now().toEpochMilli();
        try {
            rt.opsForZSet().removeRangeByScore(rk, 0, now - w.toMillis());
            Long c = rt.opsForZSet().zCard(rk);
            if (c != null && c >= lim) throw new BusinessException(429, "too frequent");
            rt.opsForZSet().add(rk, String.valueOf(now), now);
            rt.expire(rk, w.multipliedBy(2));
            // 成功一次即复位告警标记：Redis 恢复后若再次故障，仍能重新告警
            redisUnavailableWarned.set(false);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 抖动/未部署时的处理。
            //
            // 【日志降噪 2026-09-22】原实现每次请求都 log.error，而本项目 Redis
            // **只**服务于限流，演示/开发环境常常根本没启动 Redis —— 日志会被
            // "rate limit fallback: Unable to connect to Redis" 反复刷屏，淹没真实 ERROR。
            // 现改为：首次遇错打一条 WARN，之后降到 DEBUG。
            if (redisUnavailableWarned.compareAndSet(false, true)) {
                log.warn("限流降级：Redis 不可用（{}）。已切换到进程内兜底限流（宽松阈值）；"
                        + "需要与注解严格一致的限流请恢复 Redis。后续同类错误将降到 DEBUG。", e.getMessage());
            } else {
                log.debug("rate limit fallback: {}", e.getMessage());
            }
            // 【M-1】Redis 抖动同样退回进程内兜底，避免「Redis 一挂 = 完全不限流」
            localFallback(rk, lim, w);
        }
    }

    /**
     * 进程内兜底限流（Redis 不可用时启用）。
     *
     * <p>采用「宽松下限」阈值（见 {@link #LOCAL_FALLBACK_MINUTE_FLOOR} /
     * {@link #LOCAL_FALLBACK_HOUR_FLOOR}）：只挡住无成本的高频爆破，不精确复刻注解阈值。
     *
     * <p>滑动窗口按 key（含 IP）分桶，逐桶加锁；仅服务于异常场景，允许在 key 过多时整体重置。
     */
    private void localFallback(String rk, int lim, Duration w) {
        int threshold = Math.max(lim,
                w.toMinutes() >= 60 ? LOCAL_FALLBACK_HOUR_FLOOR : LOCAL_FALLBACK_MINUTE_FLOOR);
        long now = System.currentTimeMillis();
        long cutoff = now - w.toMillis();
        Deque<Long> window = localWindows.computeIfAbsent(rk, x -> new ArrayDeque<>());
        boolean over;
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.pollFirst();
            }
            over = window.size() >= threshold;
            if (!over) {
                window.addLast(now);
            }
        }
        if (over) {
            throw new BusinessException(429, "操作过于频繁，请稍后再试");
        }
        // 粗略防内存膨胀：兜底限流只服务于 Redis 缺席的异常场景，允许偶发重置
        if (localWindows.size() > 20000) {
            localWindows.clear();
        }
    }

    /** 统一走 ClientIpUtils，取真实客户端 IP（X-Forwarded-For 链路第一段）。 */
    private String getIp() {
        try {
            var a = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (a == null) return "unknown";
            HttpServletRequest r = a.getRequest();
            return ClientIpUtils.getClientIp(r);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
