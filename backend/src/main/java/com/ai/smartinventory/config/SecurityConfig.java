package com.ai.smartinventory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security配置
 * 认证由JwtAuthenticationFilter + Sa-Token处理
 * 此处配置CORS、CSRF、Session管理和安全响应头
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    /**
     * 允许的跨域来源（精确列表，逗号分隔）。
     *
     * <p><b>2026-10-10 修复</b>：原默认值只有 {@code localhost}，没有 {@code 127.0.0.1}。
     * 而开发时两个地址是等价的 —— 用 {@code http://127.0.0.1:5173} 打开时，
     * **每一个 POST 都返回 403 {@code Invalid CORS request}**（GET 走 vite 代理不带 Origin 判定，
     * 所以看起来"部分能用"，更难定位），前端只弹一句「请求失败」，完全看不出是白名单问题。
     * 实测曾因此误判为登录接口坏了。
     */
    @Value("${app.cors.allowed-origins:"
            + "http://localhost:5173,http://localhost:5174,"
            + "http://127.0.0.1:5173,http://127.0.0.1:5174}")
    private String allowedOrigins;

    /**
     * 回环地址的**通配**来源，与上面的精确列表叠加生效。
     *
     * <p>为什么要有它：vite 的端口可被 {@code VITE_PORT} 改掉，精确列举永远漏一种组合；
     * 而回环地址本身只可能来自这台机器，放开它不扩大任何外部攻击面。
     * Spring 的 {@code checkOrigin} 先比精确来源、再比来源模式，所以两者可以同时配。
     *
     * <p>生产环境照旧通过 {@code CORS_ALLOWED_ORIGINS} 传真实域名（{@code application-prod.yml}
     * 不给默认值，缺失即启动失败），这些回环模式对线上无影响。
     */
    private static final List<String> LOOPBACK_ORIGIN_PATTERNS = List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://[::1]:*"
    );

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF：前后端分离 + Token认证，可安全禁用
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // 无状态Session（使用Sa-Token的Token认证）
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 公开路径在JwtAuthenticationFilter中处理，此处放行所有请求
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/**").permitAll()
            )
            // 安全响应头
            .headers(headers -> headers
                .contentTypeOptions(c -> {})
                .frameOptions(f -> f.sameOrigin())
                .xssProtection(x -> x.headerValue(org.springframework.security.web.header.writers.XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
            );
        
        return http.build();
    }
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 【2026-10-10】trim + 过滤空项：配置里手滑多打一个空格，精确匹配就会
        // 永远对不上（`http://localhost:5173 ` ≠ `http://localhost:5173`），
        // 现象同样是"莫名 403"，且比没有这项更难查。
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);
        // 回环通配叠加在精确列表之上，兜住改过端口的本地开发场景
        configuration.setAllowedOriginPatterns(LOOPBACK_ORIGIN_PATTERNS);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
