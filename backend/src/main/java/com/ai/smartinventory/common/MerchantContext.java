package com.ai.smartinventory.common;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 商户上下文工具 —— 多租户数据隔离的统一入口
 *
 * 【修复说明】原项目中 product 表带有 merchant_id 字段，init.sql 也建了索引，
 * 但全项目没有任何一处 WHERE 条件用到它（已通过全文检索确认），
 * 导致：任何商家登录后都能查看、修改、删除其他商家的商品；
 * 商家查看订单列表时传入 userId = null，实际能看到全平台所有订单。
 * 这是典型的「水平越权（IDOR）」，属于严重安全缺陷。
 *
 * 设计原则：
 * 1. 商家身份来自 Sa-Token 会话中的 merchantId（登录时写入，客户端无法伪造）；
 * 2. 管理员（admin）返回 null，表示「不限制」——即拥有跨商户的全局视图；
 * 3. 普通用户（user）返回 null，因为用户侧浏览的是全平台在售商品，
 *    其数据边界由「订单归属 userId」而非 merchantId 控制；
 * 4. 任何商品写操作都必须先调用 {@link #assertCanAccessProduct(Long)} 做归属校验。
 */
public final class MerchantContext {

    /** Sa-Token 会话中存放当前用户商户 ID 的键 */
    public static final String SESSION_MERCHANT_ID = "merchantId";

    /** 角色标识 */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_MERCHANT = "merchant";
    public static final String ROLE_USER = "user";

    /**
     * 【2026-09-28 新增】客服角色。
     *
     * <p>背景：商品退货期限改为商家按商品设置后，超期买家需要「先联系客服、
     * 由客服依据商家统一标准裁定后」才能继续申请售后。裁定人必须是独立于
     * 商家的第三方 —— 若让商家自己裁定，等于让被申请方裁决自己是否该退货，
     * 中立性不成立（这也是没有复用 merchant 角色的原因）。
     *
     * <p>数据边界：客服是**平台方**角色，不绑定单一商户，因此
     * {@link #currentMerchantId()} 对其返回 null（与 admin 一致），
     * 客服工作台可看到全平台工单；但不继承 admin 的全局写权限 ——
     * 客服只能操作 service_ticket（裁定）这一条链路。
     */
    public static final String ROLE_KEFU = "kefu";

    private MerchantContext() {
    }

    /**
     * 当前登录用户的角色，未登录返回 null。
     */
    public static String currentRole() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Object role = StpUtil.getSession().get("userRole");
        return role == null ? null : role.toString();
    }

    /**
     * 当前登录商户的 merchantId。
     * 仅当角色为 merchant 时返回有效值；admin / user / 未登录均返回 null。
     */
    public static Long currentMerchantId() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        String role = currentRole();
        if (!ROLE_MERCHANT.equals(role)) {
            return null;
        }
        Object mid = StpUtil.getSession().get(SESSION_MERCHANT_ID);
        if (mid == null) {
            return null;
        }
        try {
            return Long.valueOf(mid.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 当前登录用户 ID，未登录返回 null。
     */
    public static Long currentUserId() {
        return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
    }

    /**
     * 是否为商家角色。
     */
    public static boolean isMerchant() {
        return ROLE_MERCHANT.equals(currentRole());
    }

    /**
     * 是否拥有全局数据视图（管理员）。
     */
    public static boolean hasGlobalView() {
        return ROLE_ADMIN.equals(currentRole());
    }

    /**
     * 校验当前商家是否有权访问指定商品，无权则抛 403 业务异常。
     *
     * @param productOwnerMerchantId 目标商品的 merchant_id
     */
    public static void assertCanAccessMerchantData(Long productOwnerMerchantId) {
        if (!isMerchant()) {
            // 管理员拥有全局权限；普通用户不通过此路径访问商品写接口
            return;
        }
        Long mine = currentMerchantId();
        if (mine == null) {
            throw new BusinessException(403, "当前账号未绑定商户，无法执行该操作");
        }
        if (productOwnerMerchantId == null || !mine.equals(productOwnerMerchantId)) {
            throw new BusinessException(403, "无权操作其他商户的数据");
        }
    }
}
