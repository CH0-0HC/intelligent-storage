package com.ai.smartinventory.security;

import cn.dev33.satoken.stp.StpUtil;
import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * JWT认证过滤器
 * 基于Sa-Token实现，自动从Authorization头读取Bearer Token进行认证
 * 公开路径无需认证，其余路径需要登录
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Resource
    private UserService userService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String method = request.getMethod();
        String path = request.getRequestURI();
        
        log.debug("JWT Filter - Path: {}, Method: {}", path, method);
        
        // OPTIONS预检请求放行
        if ("OPTIONS".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 公开路径放行
        if (isPublicPath(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            // Sa-Token自动从Authorization头读取token并校验
            StpUtil.checkLogin();
            
            // 检查用户状态
            Long userId = StpUtil.getLoginIdAsLong();
            User user = userService.getById(userId);
            if (user == null || user.getStatus() != 1) {
                throw new RuntimeException("用户不存在或已被禁用");
            }
            
            // 将用户角色存入Session供Controller使用
            StpUtil.getSession().set("userRole", user.getRole());
            
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("认证失败 - Path: {}, Reason: {}", path, e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            Map<String, Object> result = new HashMap<>();
            result.put("code", 401);
            result.put("message", "未授权，请先登录");
            objectMapper.writeValue(response.getOutputStream(), result);
        }
    }
    
    /**
     * 判断是否为公开路径（无需认证即可访问）
     *
     * 【安全修复】原实现使用 path.startsWith("/api/auth/") 放行整个 /auth 前缀，
     * 导致该包下任何"未来新增"的敏感接口（如原 /auth/init 后门、
     * 若把用户管理误放进 AuthController 等）都会自动免认证。
     * 现改为白名单精确匹配，只放行真正需要匿名访问的接口。
     */
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/logout"
    );

    private boolean isPublicPath(String path, String method) {
        // 认证相关接口：仅放行登录/注册/登出，精确匹配
        if (PUBLIC_AUTH_PATHS.contains(path)) return true;

        // 商品浏览接口（仅 GET 公开）。
        // 【安全修复】原写法第三条 `path.startsWith("/api/product/")` 把前两条完全吞掉，
        // 等于对 GET 放行整个 /api/product 前缀 —— 与刚修掉的 /api/auth/ 前缀通配是同一类
        // 问题：一旦将来在 ProductController 里新增受保护的 GET 接口（如
        // /api/product/export、/api/product/admin-list），会被静默免认证。
        // 现改为「公开橱窗接口白名单 + 数字 id 详情」精确放行。
        if ("GET".equalsIgnoreCase(method)) {
            if ("/api/product/list".equals(path)) return true;
            if ("/api/product/hot".equals(path)
                    || "/api/product/recommend".equals(path)) return true;
            // 分类浏览：/api/product/category/{categoryId}
            if (path.startsWith("/api/product/category/")) return true;
            // 商品详情：/api/product/{id}，只允许纯数字 id，
            // 避免把将来新增的子路径接口（如 /api/product/export）一并放行
            if (path.startsWith("/api/product/")
                    && path.substring("/api/product/".length()).matches("^\\d+$")) {
                return true;
            }
        }

        // 上传文件公开访问
        if (path.startsWith("/api/uploads/")) return true;
        // AI状态检查公开
        if (path.equals("/api/ai/status")) return true;
        // Swagger/Knife4j文档公开
        if (path.startsWith("/api/doc.html")) return true;
        if (path.startsWith("/api/swagger-ui")) return true;
        if (path.startsWith("/api/v3/api-docs")) return true;
        if (path.startsWith("/api/swagger-resources")) return true;
        if (path.startsWith("/api/webjars")) return true;
        return false;
    }
}
