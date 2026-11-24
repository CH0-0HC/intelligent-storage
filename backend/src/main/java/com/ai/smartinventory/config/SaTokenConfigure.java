package com.ai.smartinventory.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 注解鉴权拦截器注册。
 *
 * 【本类解决的根因】
 * Sa-Token 的 {@code @SaCheckRole} / {@code @SaCheckPermission} / {@code @SaCheckLogin}
 * 只是**声明**，必须有 {@link SaInterceptor} 参与请求链才会被真正执行。
 * 项目此前唯一的 WebMvcConfigurer（{@code WebMvcConfig}）只重写了 addResourceHandlers
 * （静态资源映射），**从未重写 addInterceptors** —— 于是全库 19 处 @SaCheckRole
 * 全部形同虚设：任何**已登录**用户都能调用商家/管理员接口，包括写接口，
 * 且 {@code GET /api/user/list} 会回吐全表用户数据（含 BCrypt 密码哈希）。
 *
 * 【设计边界（刻意为之，勿随意扩大）】
 * 1. 这里**只启用注解校验**，不接管登录校验。
 *    登录态仍由 {@code JwtAuthenticationFilter}（Servlet Filter）负责，
 *    它会在每个非公开请求上执行 {@code StpUtil.checkLogin()} + 状态校验。
 *    两套机制职责分明，不重复也不冲突。
 * 2. 不修改既有的 WebMvcConfig —— 它依赖 UploadPathResolver，承担静态资源映射，
 *    与其耦合会让「上传目录唯一来源」这条约定变脆。Spring 允许多个 WebMvcConfigurer 并存。
 *
 * 【执行顺序说明（无竞态）】
 * Servlet Filter 先于 DispatcherServlet 执行，HandlerInterceptor 在后。
 * 因此拦截器做角色判定时，JwtAuthenticationFilter 写入的会话数据（userRole / merchantId）
 * 一定已经就绪。
 *
 * 【为什么没有排除公开接口】
 * SaInterceptor 只对「带 Sa-Token 注解的 HandlerMethod」做判定，没有注解的接口天然放行。
 * /auth/login、/product/list、/ai/status、/uploads/** 等公开路径均无注解，
 * 不会因为注册了拦截器而被要求登录。下面 excludePathPatterns 中的条目本身也无注解，
 * 列入只是为了排查日志时更干净（避免静态资源请求走一遍注解处理器）。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 容器错误转发路径
                        "/error",
                        // 上传文件静态伺服（由 ResourceHttpRequestHandler 处理，非 HandlerMethod）
                        "/uploads/**",
                        // Knife4j / Swagger 文档
                        "/doc.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**"
                );
    }
}
