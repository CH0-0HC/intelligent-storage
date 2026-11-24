package com.ai.smartinventory.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【P1-2】金额边界校验的行为锁定。
 *
 * 覆盖四类此前会静默落库的脏值：负数、超大值、小数位超标、null。
 */
@DisplayName("P1-2 MoneyBounds 金额边界校验")
class MoneyBoundsTest {

    // ---------- 非负金额 ----------

    @Test
    @DisplayName("null 金额：返回「请填写」提示")
    void nullAmount() {
        String err = MoneyBounds.validateNonNegative((BigDecimal) null, "商品价格");
        assertNotNull(err);
        assertTrue(err.contains("请填写"), "应提示需填写，实际：" + err);
    }

    @Test
    @DisplayName("负数金额：被拒")
    void negativeAmount() {
        assertNotNull(MoneyBounds.validateNonNegative(new BigDecimal("-1"), "商品价格"));
        assertNotNull(MoneyBounds.validateNonNegative(new BigDecimal("-0.01"), "成本价"));
    }

    @Test
    @DisplayName("零：合法（免费商品 / 零成本是允许的）")
    void zeroAmount() {
        assertNull(MoneyBounds.validateNonNegative(BigDecimal.ZERO, "商品价格"));
        assertNull(MoneyBounds.validateNonNegative(new BigDecimal("0.00"), "成本价"));
    }

    @Test
    @DisplayName("整数与两位小数：合法")
    void normalAmount() {
        assertNull(MoneyBounds.validateNonNegative(new BigDecimal("9.90"), "商品价格"));
        assertNull(MoneyBounds.validateNonNegative(new BigDecimal("100"), "商品价格"));
        assertNull(MoneyBounds.validateNonNegative(new BigDecimal("0.01"), "商品价格"));
    }

    @Test
    @DisplayName("小数位超过 2 位：被拒（防静默四舍五入）")
    void tooManyScaleDigits() {
        assertNotNull(MoneyBounds.validateNonNegative(new BigDecimal("1.234"), "商品价格"),
                "3 位小数应被拒");
        assertNotNull(MoneyBounds.validateNonNegative(new BigDecimal("0.001"), "成本价"));
    }

    @Test
    @DisplayName("边界值 99999999.99：合法（decimal(10,2) 上限）")
    void maxAmountBoundary() {
        assertNull(MoneyBounds.validateNonNegative(MoneyBounds.MAX_AMOUNT, "商品价格"));
    }

    @Test
    @DisplayName("超过上限：被拒（防 MySQL 截断）")
    void overMaxAmount() {
        assertNotNull(MoneyBounds.validateNonNegative(new BigDecimal("100000000.00"), "商品价格"),
                "刚好超 1 分即应被拒");
        assertNotNull(MoneyBounds.validateNonNegative(new BigDecimal("1e12"), "商品价格"));
    }

    // ---------- 可选金额（IfPresent） ----------

    @Test
    @DisplayName("IfPresent: null 放行（可选字段不填 / 更新时不修改）")
    void ifPresentNullAllowed() {
        assertNull(MoneyBounds.validateNonNegativeIfPresent(null, "成本价"),
                "可选字段传 null 必须放行——否则会把「不填成本」误判成校验失败");
    }

    @Test
    @DisplayName("IfPresent: 传了脏值仍被拒")
    void ifPresentStillValidates() {
        assertNotNull(MoneyBounds.validateNonNegativeIfPresent(new BigDecimal("-1"), "成本价"));
        assertNotNull(MoneyBounds.validateNonNegativeIfPresent(new BigDecimal("1.234"), "成本价"));
        assertNotNull(MoneyBounds.validateNonNegativeIfPresent(new BigDecimal("1e12"), "成本价"));
    }

    @Test
    @DisplayName("IfPresent: 合法值放行")
    void ifPresentValid() {
        assertNull(MoneyBounds.validateNonNegativeIfPresent(new BigDecimal("5.50"), "成本价"));
        assertNull(MoneyBounds.validateNonNegativeIfPresent(BigDecimal.ZERO, "成本价"));
    }

    // ---------- 非负整数 ----------

    @Test
    @DisplayName("整数 null：放行（表示不修改该字段）")
    void nullIntegerAllowed() {
        assertNull(MoneyBounds.validateNonNegative((Integer) null, "库存"));
    }

    @Test
    @DisplayName("负整数：被拒")
    void negativeInteger() {
        assertNotNull(MoneyBounds.validateNonNegative(-1, "库存"));
        assertNotNull(MoneyBounds.validateNonNegative(Integer.MIN_VALUE, "库存"));
    }

    @Test
    @DisplayName("零与非负整数：合法")
    void nonNegativeInteger() {
        assertNull(MoneyBounds.validateNonNegative(0, "库存"));
        assertNull(MoneyBounds.validateNonNegative(999, "库存"));
    }

    @Test
    @DisplayName("上限常量与列定义一致（decimal(10,2)）")
    void constantConsistency() {
        assertEquals(new BigDecimal("99999999.99"), MoneyBounds.MAX_AMOUNT);
        assertEquals(2, MoneyBounds.SCALE);
    }

    // ---------- 汇总金额（decimal(12,2)）：采购总额曾被假上限卡住 ----------

    @Test
    @DisplayName("上限常量与列定义一致（decimal(12,2) = 99999999999.99）")
    void totalAmountConstantConsistency() {
        assertEquals(new BigDecimal("99999999999.99"), MoneyBounds.MAX_TOTAL_AMOUNT);
    }

    @Test
    @DisplayName("回归：1 亿不再被拒 —— 采购总额 decimal(12,2) 装得下")
    void totalAmountAllowsOverOneHundredMillion() {
        // 缺陷复现：此前用 decimal(10,2) 口径，10 亿会被判「不能超过 99999999.99」。
        // purchase_order.total_amount 是 decimal(12,2)，10 亿完全合法。
        assertNull(MoneyBounds.validateTotalAmount(new BigDecimal("100000000.00"), "采购总额"),
                "1 亿应放行（decimal(12,2)）");
        assertNull(MoneyBounds.validateTotalAmount(new BigDecimal("1000000000.00"), "采购总额"),
                "10 亿应放行（decimal(12,2)）");
        assertNull(MoneyBounds.validateTotalAmount(MoneyBounds.MAX_TOTAL_AMOUNT, "采购总额"),
                "列上限本身必须合法");
        assertNull(MoneyBounds.validateTotalAmount(new BigDecimal("12345678901.23"), "采购总额"));
    }

    @Test
    @DisplayName("汇总金额超列上限：仍被拒（防 MySQL 截断）")
    void totalAmountOverMax() {
        assertNotNull(MoneyBounds.validateTotalAmount(new BigDecimal("100000000000.00"), "采购总额"),
                "超过 99,999,999,999.99 一分即应被拒");
        assertNotNull(MoneyBounds.validateTotalAmount(new BigDecimal("1e15"), "采购总额"));
    }

    @Test
    @DisplayName("汇总金额：负数 / 小数位超标 / null 的处理与单件口径一致")
    void totalAmountValidationRules() {
        assertNotNull(MoneyBounds.validateTotalAmount(new BigDecimal("-0.01"), "采购总额"));
        assertNotNull(MoneyBounds.validateTotalAmount(new BigDecimal("1.234"), "采购总额"));
        assertNull(MoneyBounds.validateTotalAmount(BigDecimal.ZERO, "采购总额"));
        assertNotNull(MoneyBounds.validateTotalAmount((BigDecimal) null, "采购总额"));
        assertEquals(null, MoneyBounds.validateTotalAmountIfPresent(null, "采购总额"));
    }

    @Test
    @DisplayName("两档口径互不越界：单件金额仍卡在 1 亿（不得被顺手放宽）")
    void singleAmountBoundNotRelaxed() {
        // 关键回归：修复采购总额时**不能**顺手把 MAX_AMOUNT 抬到 12,2 口径，
        // 否则商品单价 1e12 又会重新落库被截断（P1-2 的原始缺陷）。
        assertNotNull(MoneyBounds.validateNonNegative(new BigDecimal("100000000.00"), "商品价格"));
        assertNotNull(MoneyBounds.validateNonNegativeIfPresent(new BigDecimal("100000000.00"), "成本价"));
    }
}
