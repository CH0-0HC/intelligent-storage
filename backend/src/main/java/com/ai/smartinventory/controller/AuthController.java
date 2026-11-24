package com.ai.smartinventory.controller;

import com.ai.smartinventory.common.ClientIpUtils;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.dto.LoginDTO;
import com.ai.smartinventory.dto.RegisterDTO;
import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.service.UserService;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auth")
@Tag(name = "认证管理", description = "用户登录、注册、密码修改等认证接口")
public class AuthController {
    
    @Resource
    private UserService userService;
    
    // 【安全修复】原 /auth/test 与 /auth/init 调试接口已移除。
    // 这两个接口位于公开白名单前缀 /api/auth/ 下，任何未登录者均可调用，
    // /auth/init 更会把 admin/merchant/user 三个账号的密码强制重置为弱口令，
    // 属于可直接接管系统的后门。初始账号请通过 db/init.sql 或后台管理页创建。

    @Operation(summary = "用户登录", description = "使用用户名和密码登录系统，返回JWT Token")
    @RateLimit(permitsPerMinute = 5, permitsPerHour = 20)
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO loginDTO) {
        User user = userService.getUserByUsername(loginDTO.getUsername());
        if (user == null) {
            return Result.error("用户名或密码错误");
        }
        
        if (user.getStatus() == 0) {
            return Result.error("账号已被禁用");
        }
        
        // 使用加密密码验证
        if (!userService.checkPassword(loginDTO.getPassword(), user.getPassword())) {
            return Result.error("用户名或密码错误");
        }
        
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        
        StpUtil.getSession().set("userRole", user.getRole());
        // 【多商户隔离】商家登录时把 merchantId 写进服务端会话。
        // 之所以放会话而不是让前端传参：会话数据由服务端签发、客户端无法伪造，
        // 从根上杜绝「商家 A 篡改请求参数越权访问商家 B 数据」。
        // 本项目约定：商家的 merchantId 即其用户 ID（product.merchant_id = 2 对应 merchant 账号 id=2）。
        if ("merchant".equals(user.getRole())) {
            StpUtil.getSession().set(MerchantContext.SESSION_MERCHANT_ID, user.getId());
        }
        
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(ClientIpUtils.getClientIp());
        userService.updateById(user);
        
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", user.getRole());
        data.put("avatar", user.getAvatar());
        // 【多商户隔离】把 merchantId 一并返回给前端，用于首页/菜单的角色化渲染。
        // 注意：前端仅作展示用途，所有数据边界仍在服务端强制校验，不信任此值。
        data.put("merchantId", "merchant".equals(user.getRole()) ? user.getId() : null);
        
        return Result.success("登录成功", data);
    }
    
    @Operation(summary = "用户注册", description = "注册新用户账号")
    // 【P1-1 限流补全】注册是匿名入口，必须限流防批量刷号。
    @RateLimit(key = "auth:register", permitsPerMinute = 5, permitsPerHour = 20)
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterDTO registerDTO) {
        try {
            userService.register(
                registerDTO.getUsername(),
                registerDTO.getPassword(),
                registerDTO.getEmail(),
                registerDTO.getPhone(),
                registerDTO.getNickname(),
                registerDTO.getRole()
            );
            return Result.success("注册成功");
        } catch (RuntimeException e) {
            // 【异常出口收口】收窄为 RuntimeException：这里只会抛业务异常
            // （如 "用户名已存在"），消息可读、可原样返回。
            // 若写成 catch (Exception) 会把一切异常都吞掉并回吐原始消息，
            // 既可能泄露内部结构，也会架空 GlobalExceptionHandler。
            return Result.error(e.getMessage());
        }
    }
    
    @PostMapping("/logout")
    public Result<String> logout() {
        StpUtil.logout();
        return Result.success("退出登录成功");
    }
    
    @Operation(summary = "获取当前用户信息", description = "获取已登录用户的详细信息")
    @GetMapping("/currentUser")
    public Result<Map<String, Object>> getCurrentUser() {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userService.getById(userId);
        
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", user.getRole());
        data.put("avatar", user.getAvatar());
        data.put("phone", user.getPhone());
        data.put("email", user.getEmail());
        data.put("merchantId", "merchant".equals(user.getRole()) ? user.getId() : null);
        
        return Result.success(data);
    }
    
    @Operation(summary = "修改密码", description = "修改当前登录用户的密码")
    // 【P1-1 限流补全】改密是凭据操作，防暴力试探原密码。
    @RateLimit(key = "auth:updatePassword", permitsPerMinute = 10, permitsPerHour = 50)
    @PostMapping("/updatePassword")
    public Result<String> updatePassword(@RequestBody Map<String, String> params) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        // 【参数校验】newPassword 为空时会走到 BCrypt 的 encode(null)，此前只回吐原始异常消息
        if (params == null || params.get("newPassword") == null || params.get("newPassword").isBlank()) {
            return Result.error(400, "请输入新密码");
        }
        
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            userService.updatePassword(userId, params.get("oldPassword"), params.get("newPassword"));
            return Result.success("密码修改成功");
        } catch (RuntimeException e) {
            // 【异常出口收口】收窄为 RuntimeException，保留 "原密码错误" 这类可读业务消息
            return Result.error(e.getMessage());
        }
    }

    /**
     * 【新增】自助修改个人资料（昵称 / 邮箱 / 手机号）。
     *
     * 【为什么必须单独立这个接口】
     * 前端「个人中心」页（views/user/Profile.vue）原先调用的是 PUT /user/update，
     * 而 UserController 是**类级 @SaCheckRole("admin")** —— 一旦注册 SaInterceptor
     * 让角色注解真正生效，商家与普通用户在这页点「保存修改」会立刻 403，功能当场锁死。
     * 而个人中心路由的 meta.roles 是 ['admin','merchant','user']，三个角色都能进这页。
     *
     * 因此把「改自己的资料」从「管理用户」中拆出来，落到认证域（/auth）下：
     *   - PUT /user/update 保持 admin-only（后台管理他人账号，含改角色/状态/重置密码）；
     *   - PUT /auth/profile 面向任意已登录用户，只能改自己。
     *
     * 【安全要点（三点，缺一不可）】
     * 1. **id 强制取当前登录用户**，绝不从请求体读取 —— 原 PUT /user/update 直接
     *    updateById，不校验归属，任意登录用户传 {"id":1,"password":"x"} 即可改掉
     *    管理员口令，是实打实的提权；这里从根上堵住。
     * 2. **只接受 nickname / email / phone 三个字段**（用 Map 而非直接绑定 User 实体），
     *    请求体里即使塞了 password / role / status / deleted 也会被丢弃 ——
     *    否则普通用户可自行提权为 admin。
     * 3. 使用 LambdaUpdateWrapper 显式 set，而不是 updateById(实体)。
     *    updateById 会跳过 null 字段，若三个字段恰好全为 null 就会生成
     *    「UPDATE sys_user SET WHERE id=?」这种非法 SQL；显式 set 不存在该问题。
     *
     * 本接口不加 @SaCheckRole（任何已登录用户都该能改自己的资料）；
     * 路径 /api/auth/profile 不在 JwtAuthenticationFilter 的公开白名单内，仍需登录。
     */
    @Operation(summary = "修改个人资料", description = "修改当前登录用户的昵称、邮箱、手机号")
    @SaCheckLogin
    // 【P1-1 限流补全】资料写操作，限流防刷。
    @RateLimit(key = "auth:profile", permitsPerMinute = 20, permitsPerHour = 100)
    @PutMapping("/profile")
    public Result<String> updateProfile(@RequestBody(required = false) Map<String, String> params) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        if (params == null || params.isEmpty()) {
            return Result.error(400, "没有需要更新的字段");
        }

        Long userId = StpUtil.getLoginIdAsLong();

        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId);

        // 只允许这三项，且仅在请求体显式给出时才更新（未给出的字段保持原值）
        boolean anyField = false;
        if (params.containsKey("nickname")) {
            wrapper.set(User::getNickname, params.get("nickname"));
            anyField = true;
        }
        if (params.containsKey("email")) {
            wrapper.set(User::getEmail, params.get("email"));
            anyField = true;
        }
        if (params.containsKey("phone")) {
            wrapper.set(User::getPhone, params.get("phone"));
            anyField = true;
        }

        if (!anyField) {
            return Result.error(400, "仅支持修改昵称、邮箱、手机号");
        }

        userService.update(wrapper);
        log.info("用户 {} 自助修改个人资料", userId);
        return Result.success("保存成功");
    }
}
