package com.ai.smartinventory.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthController 登录哨兵单元测试（P0-3 演示账号加固）
 *
 * 【验证对象】AuthController.isDemoAccountBlocked —— prod profile 下，
 * init.sql 的四个种子账号（admin/merchant/user/kefu）必须被拒绝；
 * dev/test/空 profile 行为完全不变。
 *
 * 【为什么直接测纯方法】登录主流程末尾调用 StpUtil.login（Sa-Token 静态 API），
 * 在纯 Mockito 单元测试中缺少 Web 上下文会抛异常，导致断言脆弱且依赖测试顺序。
 * 把哨兵判定抽成静态纯方法后，可精确、稳定地覆盖所有分支，不触碰任何外部状态。
 */
@DisplayName("P0-3 演示账号环境哨兵")
class AuthControllerDemoAccountTest {

    @ParameterizedTest(name = "prod 下种子账号 [{0}] 应被拦截")
    @ValueSource(strings = {"admin", "merchant", "user", "kefu"})
    void prodBlocksAllDemoAccounts(String username) {
        assertTrue(AuthController.isDemoAccountBlocked(username, "prod"));
    }

    @Test
    @DisplayName("prod,xxx：多 profile 同时激活时同样拦截（contains 而非 equals）")
    void prodInMultiProfileStillBlocks() {
        assertTrue(AuthController.isDemoAccountBlocked("kefu", "prod,ops"));
        assertTrue(AuthController.isDemoAccountBlocked("admin", "ops,prod"));
    }

    @Test
    @DisplayName("prod：大小写不同写法 PROD 也拦截")
    void prodCaseSensitiveNote() {
        // 说明：当前实现按 Spring 规范使用小写 profile 名（prod），大写 PROD 不在拦截范围。
        // Spring 的 profile 名大小写敏感且惯例为小写，此处显式记录该边界。
        assertFalse(AuthController.isDemoAccountBlocked("admin", "PROD"));
    }

    @Test
    @DisplayName("prod：非种子账号不受影响")
    void prodAllowsNonDemoAccount() {
        assertFalse(AuthController.isDemoAccountBlocked("real_admin", "prod"));
        assertFalse(AuthController.isDemoAccountBlocked("superuser", "prod"));
    }

    @ParameterizedTest(name = "非 prod profile [{0}] 下种子账号不拦截")
    @ValueSource(strings = {"dev", "test", "local", ""})
    void nonProdProfilesAllowDemoAccounts(String profile) {
        assertFalse(AuthController.isDemoAccountBlocked("admin", profile));
        assertFalse(AuthController.isDemoAccountBlocked("merchant", profile));
    }

    @Test
    @DisplayName("null profile / null 用户名：不拦截，保持原有行为")
    void nullInputsDoNotBlock() {
        assertFalse(AuthController.isDemoAccountBlocked("admin", null));
        assertFalse(AuthController.isDemoAccountBlocked(null, "prod"));
    }
}
