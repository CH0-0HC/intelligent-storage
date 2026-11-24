package com.ai.smartinventory.security;

import cn.dev33.satoken.stp.StpInterface;
import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限/角色数据源。
 *
 * 【为什么必须有这个类】
 * Sa-Token 的 {@code @SaCheckRole} 只是「声明」，真正做判定的是注解处理器：
 * 它拿到当前会话的 loginId 后，会回调本接口的 {@link #getRoleList} 问「这个人有哪些角色」。
 * 全项目此前**没有任何 StpInterface 实现**，也没有注册 SaInterceptor（见 SaTokenConfigure），
 * 于是所有 @SaCheckRole 注解形同虚设 —— 任意已登录用户都能调用商家/管理员接口。
 *
 * 【为什么角色查库而不是读会话】
 * 会话里的 userRole 只在「登录时」和「JwtAuthenticationFilter 每次已登录请求」写入，
 * 一旦会话被 Sa-Token 淘汰/超时（token 仍有效、但会话数据不在），读会话会返回空，
 * 结果是**管理员自己也被 403 锁死**。查库则是权威来源，且：
 *   1. 走 sys_user 主键，命中极快；
 *   2. 角色以数据库当前值为准 —— 后台改了角色立即生效，不会被会话里的旧值拖住。
 * 代价是每个带角色注解的请求多一次主键查询，与 JwtAuthenticationFilter 已有的
 * getById 同量级，安全收益远大于该开销。
 */
@Slf4j
@Component
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private UserService userService;

    /**
     * 权限码列表。
     *
     * 本项目不使用权限码（全库无 {@code @SaCheckPermission} / {@code SaRouter.match().check(...)} 用法），
     * 因此恒返回空列表。若将来引入权限码，必须在此实现，否则相关注解会全部拒绝。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    /**
     * 角色列表。Sa-Token 的 {@code @SaCheckRole(mode = SaMode.OR)} 判定的是「是否命中其中之一」，
     * 因此这里返回单元素列表即可（sys_user.role 为单值字段）。
     *
     * @param loginId 当前会话的登录 id（这里即 sys_user.id）
     * @return 角色列表；无法解析/用户不存在/角色为空时返回空列表（会被判定为「无该角色」）
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = parseLoginId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }

        User user = userService.getById(userId);
        if (user == null) {
            log.warn("角色解析：loginId={} 对应用户不存在（可能已被删除），按无角色处理", userId);
            return Collections.emptyList();
        }
        String role = user.getRole();
        if (role == null || role.isBlank()) {
            log.warn("角色解析：用户 id={} 的 role 字段为空，按无角色处理", userId);
            return Collections.emptyList();
        }
        return Collections.singletonList(role);
    }

    private Long parseLoginId(Object loginId) {
        if (loginId == null) {
            log.warn("角色解析：loginId 为 null");
            return null;
        }
        try {
            return Long.valueOf(loginId.toString());
        } catch (NumberFormatException e) {
            log.warn("角色解析：loginId 无法转为 Long，值={}", loginId);
            return null;
        }
    }
}
