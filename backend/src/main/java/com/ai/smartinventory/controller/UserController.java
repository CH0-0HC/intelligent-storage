package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@SaCheckRole("admin")
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @GetMapping("/list")
    public Result<List<User>> listUsers() {
        List<User> users = userService.list(
            new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreateTime)
        );
        return Result.success(users);
    }

    // 【P1-1 限流补全】建号是 admin 敏感写操作（可造管理员），收紧限流。
    @RateLimit(key = "user:add", permitsPerMinute = 10, permitsPerHour = 50)
    @PostMapping("/add")
    public Result<String> addUser(@RequestBody User user) {
        // 【参数校验】此前一个都不校验：username 为空会撞该列的 NOT NULL/UNIQUE，
        // password 为空则 encodePassword(null) 抛 IllegalArgumentException，
        // 两者最终都以「系统繁忙，请稍后重试」呈现。
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            return Result.error(400, "请填写用户名");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            return Result.error(400, "请填写密码");
        }
        user.setPassword(userService.encodePassword(user.getPassword()));
        user.setDeleted(0);
        user.setStatus(1);
        userService.save(user);
        return Result.success("添加成功");
    }

    // 【P1-1 限流补全】改他人账号（含角色/状态）是管理员敏感写操作，收紧限流。
    @RateLimit(key = "user:update", permitsPerMinute = 20, permitsPerHour = 100)
    @PutMapping("/update")
    public Result<String> updateUser(@RequestBody User user) {
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(userService.encodePassword(user.getPassword()));
        }
        userService.updateById(user);
        return Result.success("更新成功");
    }

    // 【修复】移除方法级 @SaCheckRole(admin OR merchant)。
    // 类级已是 @SaCheckRole("admin")，Sa-Token 的校验是「类级 + 方法级都要满足」，
    // 方法级声明 OR merchant 并不会放行商家，只会造成「看起来商家能删用户」的误解，
    // 属于误导性冗余声明。删除用户是纯平台治理动作，保持仅 admin 可执行。
    // 【修复】原文件里 @DeleteMapping("/delete/{id}") 与上面这行注释写在了**同一行**，
    // 注解被 // 吞掉（Java 单行注释吞到行尾）—— 结果是 deleteUser 没有任何路由映射，
    // 前端 api/user.js#deleteUser() 打 DELETE /user/delete/{id} 必然 404。现将注解独立成行。
    // 【P1-1 限流补全】删号是破坏性操作，收紧限流。
    @RateLimit(key = "user:delete", permitsPerMinute = 20, permitsPerHour = 100)
    @DeleteMapping("/delete/{id}")
    public Result<String> deleteUser(@PathVariable Long id) {
        // 【修复】原实现手动 setDeleted(1) + updateById，
        // 看似等价于逻辑删除，实际绕过了 MyBatis-Plus 的 @TableLogic 机制：
        // 一旦实体/全局配置调整删除标记语义，这里就会静默失效留下脏数据。
        // 改为调用框架的 removeById，由 @TableLogic 统一处理。
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        userService.removeById(id);
        return Result.success("删除成功");
    }
}
