package com.ai.smartinventory.common;

import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.entity.AfterSale;
import com.ai.smartinventory.entity.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AfterSaleRule 单元测试（售后规则中心）
 *
 * 【为什么这个类最该被测】
 * 它是「能不能申请售后」的**唯一判定出口**，前端入口是否出现、置灰用什么文案、
 * 提交接口是否放行，三处全部消费它的结论。一旦这里有逻辑回归：
 *   - 前端出现「按钮可点但提交被拒」→ 用户永远等不到成功；
 *   - 或「已过期却仍可提交」→ 商家被迫接受超期售后。
 * 两种都是线上事故，且都不容易被手工点测覆盖到（时间窗口要靠等待）。
 * 而 evaluate() 显式接收 now 参数，天生适合用固定时间做时间旅行断言。
 *
 * 【覆盖策略】
 * 1) 订单状态分支：0 未付款 / 1 已付款 / 2 已发货 / 3 已完成 / 4 已取消 / 其它
 * 2) R15 兜底：order_status=3 但 pay_status != 1（历史脏数据）
 * 3) 一单一售后：进行中(PENDING/AGREED) 与 已完成(FINISHED) 的优先级
 * 4) 时效窗口：15 天临界（含/不含）、7 天无理由临界、daysLeft 计算
 * 5) completeTime 为空的降级路径（用 updateTime 兜底 + degraded 标记）
 * 6) 金额校验边界：0 / 负数 / 超额 / 容差 0.01 / null
 * 7) 入口形态 entryState 与 eligible/hasAfterSale 的一致性
 *
 * 注意：本类不依赖 Spring，纯静态调用，无需 Mockito。
 */
@DisplayName("售后规则中心单元测试")
class AfterSaleRuleTest {

    /** 固定基准时间：所有时间旅行断言都以它为 now，避免 flaky */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0, 0);

    // ==================== 构造辅助 ====================

    /**
     * 造一个「已确认收货且已付款」的订单 —— 唯一合法可申请售后的形态。
     * 各测试再按需覆写单个字段，保证其它前置条件始终成立，
     * 这样断言失败时能立刻定位到「就是那个被改的字段」导致的。
     */
    private Order completedPaidOrder() {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo("NO20260927001");
        o.setUserId(2L);
        o.setOrderStatus(AfterSaleRule.ORDER_STATUS_COMPLETED);
        o.setPayStatus(1);
        o.setPayAmount(new BigDecimal("199.00"));
        o.setCompleteTime(NOW.minusDays(1)); // 昨天收货，默认处于窗口内
        o.setUpdateTime(NOW.minusDays(1));
        return o;
    }

    private AfterSale afterSale(Long id, int status, int type) {
        AfterSale a = new AfterSale();
        a.setId(id);
        a.setOrderId(1L);
        a.setStatus(status);
        a.setType(type);
        return a;
    }

    // ==================== 1. 订单状态分支 ====================

    @Nested
    @DisplayName("订单状态分支")
    class OrderStatusBranch {

        @Test
        @DisplayName("未付款(0) → UNPAID，不可申请")
        void unpaid() {
            Order o = completedPaidOrder();
            o.setOrderStatus(AfterSaleRule.ORDER_STATUS_UNPAID);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_UNPAID, r.getCode());
            assertEquals("DISABLED", r.getEntryState());
        }

        @Test
        @DisplayName("已付款(1) → NOT_RECEIVED，货未到手不开放售后")
        void paid() {
            Order o = completedPaidOrder();
            o.setOrderStatus(AfterSaleRule.ORDER_STATUS_PAID);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_NOT_RECEIVED, r.getCode());
        }

        @Test
        @DisplayName("已发货(2) → NOT_RECEIVED，必须等确认收货")
        void shipped() {
            Order o = completedPaidOrder();
            o.setOrderStatus(AfterSaleRule.ORDER_STATUS_SHIPPED);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_NOT_RECEIVED, r.getCode());
        }

        @Test
        @DisplayName("已取消(4) → CANCELED")
        void canceled() {
            Order o = completedPaidOrder();
            o.setOrderStatus(AfterSaleRule.ORDER_STATUS_CANCELED);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_CANCELED, r.getCode());
        }

        @Test
        @DisplayName("未知状态(99) → UNSUPPORTED，不得误判为可申请")
        void unknownStatus() {
            Order o = completedPaidOrder();
            o.setOrderStatus(99);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_UNSUPPORTED, r.getCode());
        }

        @Test
        @DisplayName("orderStatus 为 null → 按不支持处理，不抛 NPE")
        void nullOrderStatus() {
            Order o = completedPaidOrder();
            o.setOrderStatus(null);

            AfterSaleEligibility r = assertDoesNotThrow(() -> AfterSaleRule.evaluate(o, null, NOW));
            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_UNSUPPORTED, r.getCode());
        }

        @Test
        @DisplayName("order 为 null → UNSUPPORTED（不存在/无权访问）")
        void nullOrder() {
            AfterSaleEligibility r = AfterSaleRule.evaluate(null, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_UNSUPPORTED, r.getCode());
        }
    }

    // ==================== 2. R15 未付款兜底 ====================

    @Nested
    @DisplayName("R15 未付款兜底（历史脏数据）")
    class R15NotPaidGuard {

        @Test
        @DisplayName("order_status=3 但 pay_status=0 → NOT_PAID，不得放行")
        void completedButUnpaid() {
            Order o = completedPaidOrder();
            o.setPayStatus(0); // 已完成却从未付款

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible(),
                    "『已完成但未付款』是往外退钱的接口，必须独立拦一次");
            assertEquals(AfterSaleRule.CODE_NOT_PAID, r.getCode());
            assertEquals("DISABLED", r.getEntryState());
        }

        @Test
        @DisplayName("pay_status 为 null → NOT_PAID（不得当已付款处理）")
        void nullPayStatus() {
            Order o = completedPaidOrder();
            o.setPayStatus(null);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_NOT_PAID, r.getCode());
        }

        @Test
        @DisplayName("pay_status=2 等异常值 → 一律 NOT_PAID（只认 ==1）")
        void abnormalPayStatus() {
            Order o = completedPaidOrder();
            o.setPayStatus(2);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_NOT_PAID, r.getCode());
        }

        @Test
        @DisplayName("未付款兜底优先于时效判断：即使未过期也拦")
        void notPaidTakesPrecedenceOverWindow() {
            Order o = completedPaidOrder();
            o.setPayStatus(0);
            o.setCompleteTime(NOW.minusHours(1)); // 刚收货，时效绝对没过

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertEquals(AfterSaleRule.CODE_NOT_PAID, r.getCode(),
                    "未付款检查必须在时效检查之前，否则脏数据会绕过闸门");
        }
    }

    // ==================== 3. 一单一售后 ====================

    @Nested
    @DisplayName("一单一售后")
    class OneOrderOneAfterSale {

        @Test
        @DisplayName("进行中(PENDING) → PROCESSING，优先于时效判断")
        void pendingBlocks() {
            Order o = completedPaidOrder();
            // 即使时效已过，也应先报「处理中」而不是「已过期」——用户看到的原因必须准确
            o.setCompleteTime(NOW.minusDays(30));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(10L, AfterSaleRule.STATUS_PENDING, AfterSaleRule.TYPE_REFUND_ONLY)), NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_PROCESSING, r.getCode());
            assertTrue(r.getHasAfterSale());
            assertEquals(10L, r.getLatestAfterSaleId());
            assertEquals("VIEW", r.getEntryState());
        }

        @Test
        @DisplayName("已同意(AGREED) → PROCESSING")
        void agreedBlocks() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(11L, AfterSaleRule.STATUS_AGREED, AfterSaleRule.TYPE_RETURN_REFUND)), NOW);

            assertEquals(AfterSaleRule.CODE_PROCESSING, r.getCode());
        }

        @Test
        @DisplayName("已完成(FINISHED) → FINISHED，一单一售后终态")
        void finishedBlocks() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(12L, AfterSaleRule.STATUS_FINISHED, AfterSaleRule.TYPE_REFUND_ONLY)), NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_FINISHED, r.getCode());
            assertEquals("VIEW", r.getEntryState());
        }

        @Test
        @DisplayName("【允许重申请】已拒绝(REJECTED) 且未过期 → 仍可 ELIGIBLE")
        void rejectedCanReapply() {
            Order o = completedPaidOrder(); // 昨天收货，窗口内

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(13L, AfterSaleRule.STATUS_REJECTED, AfterSaleRule.TYPE_REFUND_ONLY)), NOW);

            assertTrue(r.getEligible(), "被拒后剩余时效内应允许重新申请");
            assertEquals(AfterSaleRule.CODE_ELIGIBLE, r.getCode());
            // hasAfterSale 仍为 true（历史上确实存在过售后单），但入口是 APPLY
            assertTrue(r.getHasAfterSale());
            assertEquals("APPLY", r.getEntryState(),
                    "entryState 必须由 eligible 优先裁定，不能被 hasAfterSale 带偏");
        }

        @Test
        @DisplayName("【允许重申请】已撤销(CANCELLED) 且未过期 → 仍可 ELIGIBLE")
        void cancelledCanReapply() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(14L, AfterSaleRule.STATUS_CANCELLED, AfterSaleRule.TYPE_REFUND_ONLY)), NOW);

            assertTrue(r.getEligible());
            assertEquals(AfterSaleRule.CODE_ELIGIBLE, r.getCode());
        }

        @Test
        @DisplayName("多条售后单 → 取 id 最大者作为 latest（id 递增即时间递增）")
        void latestIsMaxId() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, List.of(
                    afterSale(1L, AfterSaleRule.STATUS_FINISHED, AfterSaleRule.TYPE_REFUND_ONLY),
                    afterSale(9L, AfterSaleRule.STATUS_REJECTED, AfterSaleRule.TYPE_REFUND_ONLY),
                    afterSale(5L, AfterSaleRule.STATUS_CANCELLED, AfterSaleRule.TYPE_REFUND_ONLY)
            ), NOW);

            assertEquals(9L, r.getLatestAfterSaleId(), "必须取 id 最大的一条，与入参顺序无关");
            assertEquals(AfterSaleRule.STATUS_REJECTED, r.getLatestStatus());
            assertTrue(r.getEligible(), "最近一条是被拒 → 可重申请");
        }

        @Test
        @DisplayName("history 列表含 null 元素 → 过滤掉，不抛 NPE")
        void historyWithNullElement() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = assertDoesNotThrow(() -> AfterSaleRule.evaluate(o,
                    java.util.Arrays.asList(null, afterSale(7L, AfterSaleRule.STATUS_REJECTED, AfterSaleRule.TYPE_REFUND_ONLY)), NOW));

            assertEquals(7L, r.getLatestAfterSaleId());
        }

        @Test
        @DisplayName("history 为空集合 → 视为无售后单")
        void emptyHistory() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, List.of(), NOW);

            assertFalse(r.getHasAfterSale());
            assertTrue(r.getEligible());
        }
    }

    // ==================== 4. 时效窗口 ====================

    @Nested
    @DisplayName("时效窗口（15 天 / 7 天无理由）")
    class TimeWindow {

        @Test
        @DisplayName("刚收货 → 15 天与 7 天都满额剩余")
        void justReceived() {
            Order o = completedPaidOrder();
            o.setCompleteTime(NOW);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertTrue(r.getEligible());
            assertEquals(AfterSaleRule.CODE_ELIGIBLE, r.getCode());
            assertEquals(15, r.getDaysLeft());
            assertEquals(7, r.getNoReasonDaysLeft());
            assertEquals("APPLY", r.getEntryState());
            assertFalse(r.getExpired());
        }

        @Test
        @DisplayName("收货第 7 天 → 无理由剩余 0，但仍可申请有理由售后")
        void day7NoReasonExhausted() {
            Order o = completedPaidOrder();
            o.setCompleteTime(NOW.minusDays(7));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertTrue(r.getEligible(), "第 7 天仍在 15 天窗口内");
            assertEquals(0, r.getNoReasonDaysLeft());
            assertEquals(8, r.getDaysLeft());
            // 提示文案应切换到「超过7天无理由」口径，而非继续报剩余天数
            assertTrue(r.getHint().contains("7 天无理由"), "实际文案：" + r.getHint());
        }

        @Test
        @DisplayName("收货第 6 天 → 无理由还剩 1 天（临界前一天）")
        void day6NoReasonStillAvailable() {
            Order o = completedPaidOrder();
            o.setCompleteTime(NOW.minusDays(6));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertEquals(1, r.getNoReasonDaysLeft());
            assertTrue(r.getHint().contains("1 天"), "实际文案：" + r.getHint());
        }

        @Test
        @DisplayName("收货第 15 天且未超过 deadline → 仍 ELIGIBLE，daysLeft=0")
        void day15BoundaryStillEligible() {
            Order o = completedPaidOrder();
            // 15 天窗口的 deadline = completeTime + 15 天。
            // 取 completeTime = NOW - 15 天 → deadline = NOW，now.isAfter(deadline) 为 false ⇒ 放行。
            o.setCompleteTime(NOW.minusDays(15));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertTrue(r.getEligible(), "恰好命中 deadline 当天不应被判定过期（闭区间）");
            assertEquals(0, r.getDaysLeft());
        }

        @Test
        @DisplayName("超过 deadline 一秒 → EXPIRED（边界外）")
        void justOverDeadline() {
            Order o = completedPaidOrder();
            // NOW 比 deadline 晚 1 秒
            o.setCompleteTime(NOW.minusDays(15).minusSeconds(1));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_EXPIRED, r.getCode());
            assertTrue(r.getExpired());
            assertEquals("DISABLED", r.getEntryState());
        }

        @Test
        @DisplayName("收货 30 天 → EXPIRED")
        void longExpired() {
            Order o = completedPaidOrder();
            o.setCompleteTime(NOW.minusDays(30));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_EXPIRED, r.getCode());
            assertTrue(r.getHint().contains("15 天"), "实际文案：" + r.getHint());
        }

        @Test
        @DisplayName("completeTime 为空 → 用 updateTime 兜底并标记 degraded")
        void degradedWhenCompleteTimeMissing() {
            Order o = completedPaidOrder();
            o.setCompleteTime(null);
            o.setUpdateTime(NOW.minusDays(2));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertTrue(r.getEligible(), "历史缺记签收时间不应导致永久无法申请");
            assertTrue(r.getDegraded(), "降级推算必须显式标记，便于排查");
            assertEquals(13, r.getDaysLeft());
        }

        @Test
        @DisplayName("completeTime 与 updateTime 都为空 → EXPIRED 且不得抛 NPE")
        void noTimeAtAll() {
            Order o = completedPaidOrder();
            o.setCompleteTime(null);
            o.setUpdateTime(null);

            AfterSaleEligibility r = assertDoesNotThrow(() -> AfterSaleRule.evaluate(o, null, NOW));

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_EXPIRED, r.getCode());
            assertTrue(r.getExpired());
        }

        @Test
        @DisplayName("时效过期优先于「已拒绝可重申请」：过期后仍拒绝")
        void expiredBeatsReapply() {
            Order o = completedPaidOrder();
            o.setCompleteTime(NOW.minusDays(30));

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(20L, AfterSaleRule.STATUS_REJECTED, AfterSaleRule.TYPE_REFUND_ONLY)), NOW);

            assertFalse(r.getEligible(), "重申请也要受时效约束");
            assertEquals(AfterSaleRule.CODE_EXPIRED, r.getCode());
        }
    }

    // ==================== 5. 金额校验 ====================

    @Nested
    @DisplayName("退款金额校验")
    class RefundAmount {

        private final BigDecimal PAY = new BigDecimal("199.00");

        @Test
        @DisplayName("正常金额 → 合法")
        void normal() {
            assertTrue(AfterSaleRule.isValidRefundAmount(new BigDecimal("199.00"), PAY));
            assertTrue(AfterSaleRule.isValidRefundAmount(new BigDecimal("0.01"), PAY));
        }

        @Test
        @DisplayName("容差 0.01：超出实付 1 分以内仍合法（防浮点/分位误差误拒）")
        void tolerance() {
            assertTrue(AfterSaleRule.isValidRefundAmount(new BigDecimal("199.01"), PAY),
                    "容差内应放行");
            assertFalse(AfterSaleRule.isValidRefundAmount(new BigDecimal("199.02"), PAY),
                    "超出容差 1 分以上必须拒绝");
        }

        @Test
        @DisplayName("0 元 → 非法（退款必须有金额）")
        void zero() {
            assertFalse(AfterSaleRule.isValidRefundAmount(BigDecimal.ZERO, PAY));
        }

        @Test
        @DisplayName("负数 → 非法")
        void negative() {
            assertFalse(AfterSaleRule.isValidRefundAmount(new BigDecimal("-1"), PAY));
        }

        @Test
        @DisplayName("超过实付金额 → 非法（防超额退款）")
        void exceeds() {
            assertFalse(AfterSaleRule.isValidRefundAmount(new BigDecimal("999"), PAY));
        }

        @Test
        @DisplayName("null 金额或 null 实付 → 一律非法，不抛 NPE")
        void nulls() {
            assertFalse(AfterSaleRule.isValidRefundAmount(null, PAY));
            assertFalse(AfterSaleRule.isValidRefundAmount(new BigDecimal("10"), null));
            assertFalse(AfterSaleRule.isValidRefundAmount(null, null));
        }
    }

    // ==================== 6. 枚举与工具方法 ====================

    @Nested
    @DisplayName("类型 / 原因 / 状态枚举")
    class Enums {

        @Test
        @DisplayName("isValidType：只认 1/2/3，null 与其它值非法")
        void validType() {
            assertTrue(AfterSaleRule.isValidType(AfterSaleRule.TYPE_REFUND_ONLY));
            assertTrue(AfterSaleRule.isValidType(AfterSaleRule.TYPE_RETURN_REFUND));
            assertTrue(AfterSaleRule.isValidType(AfterSaleRule.TYPE_EXCHANGE));
            assertFalse(AfterSaleRule.isValidType(null));
            assertFalse(AfterSaleRule.isValidType(0));
            assertFalse(AfterSaleRule.isValidType(4));
        }

        @Test
        @DisplayName("allowsNoReason：仅退款/退货退款可选无理由，换货不行")
        void allowsNoReason() {
            assertTrue(AfterSaleRule.allowsNoReason(AfterSaleRule.TYPE_REFUND_ONLY));
            assertTrue(AfterSaleRule.allowsNoReason(AfterSaleRule.TYPE_RETURN_REFUND));
            assertFalse(AfterSaleRule.allowsNoReason(AfterSaleRule.TYPE_EXCHANGE),
                    "换货必须有明确理由，不能选『7天无理由』");
            assertFalse(AfterSaleRule.allowsNoReason(null));
        }

        @Test
        @DisplayName("isActive：仅 PENDING/AGREED 占用「一单一售后」额度")
        void activeStatus() {
            assertTrue(AfterSaleRule.isActive(AfterSaleRule.STATUS_PENDING));
            assertTrue(AfterSaleRule.isActive(AfterSaleRule.STATUS_AGREED));
            assertFalse(AfterSaleRule.isActive(AfterSaleRule.STATUS_FINISHED));
            assertFalse(AfterSaleRule.isActive(AfterSaleRule.STATUS_REJECTED));
            assertFalse(AfterSaleRule.isActive(AfterSaleRule.STATUS_CANCELLED));
        }

        @Test
        @DisplayName("isValidReason：合法编码通过，未知/null 拒绝")
        void validReason() {
            assertTrue(AfterSaleRule.isValidReason(AfterSaleRule.REASON_NO_REASON));
            assertTrue(AfterSaleRule.isValidReason(AfterSaleRule.REASON_QUALITY));
            assertFalse(AfterSaleRule.isValidReason("hacked_reason"));
            assertFalse(AfterSaleRule.isValidReason(null));
        }

        @Test
        @DisplayName("reasonText：未知编码回退『其他原因』，不返回 null")
        void reasonTextFallback() {
            assertEquals("其他原因", AfterSaleRule.reasonText("unknown_xyz"));
            assertEquals("质量问题", AfterSaleRule.reasonText(AfterSaleRule.REASON_QUALITY));
            assertNotNull(AfterSaleRule.reasonText(null));
        }

        @Test
        @DisplayName("reasonCatalog：返回副本，外部修改不污染内部规则表")
        void reasonCatalogIsDefensiveCopy() {
            var first = AfterSaleRule.reasonCatalog();
            int sizeBefore = first.size();
            first.put("injected", "注入的假原因");

            assertEquals(sizeBefore, AfterSaleRule.reasonCatalog().size(),
                    "必须返回副本，否则外部可直接篡改可选原因");
            assertFalse(AfterSaleRule.isValidReason("injected"));
        }
    }

    // ==================== 7. 入口形态一致性 ====================

    @Nested
    @DisplayName("entryState 派生一致性")
    class EntryState {

        @Test
        @DisplayName("eligible=true → APPLY（即使历史上有过售后单）")
        void eligibleWinsOverHasAfterSale() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(30L, AfterSaleRule.STATUS_REJECTED, AfterSaleRule.TYPE_REFUND_ONLY)), NOW);

            assertTrue(r.getEligible());
            assertTrue(r.getHasAfterSale());
            assertEquals("APPLY", r.getEntryState());
        }

        @Test
        @DisplayName("eligible=false 且有售后单 → VIEW（可查看进度）")
        void viewWhenHasAfterSale() {
            Order o = completedPaidOrder();

            AfterSaleEligibility r = AfterSaleRule.evaluate(o,
                    List.of(afterSale(31L, AfterSaleRule.STATUS_PENDING, AfterSaleRule.TYPE_REFUND_ONLY)), NOW);

            assertEquals("VIEW", r.getEntryState());
        }

        @Test
        @DisplayName("eligible=false 且无售后单 → DISABLED（置灰 + hint）")
        void disabledWhenNothing() {
            Order o = completedPaidOrder();
            o.setOrderStatus(AfterSaleRule.ORDER_STATUS_SHIPPED);

            AfterSaleEligibility r = AfterSaleRule.evaluate(o, null, NOW);

            assertEquals("DISABLED", r.getEntryState());
            assertNotNull(r.getHint(), "置灰必须带 hint，否则前端无处取文案");
            assertFalse(r.getHint().isBlank());
        }
    }
}
