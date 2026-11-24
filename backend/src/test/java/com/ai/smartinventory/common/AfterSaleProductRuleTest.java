package com.ai.smartinventory.common;

import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.ServiceTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【2026-09-28】商品级退货期限 + 客服放行通道 的规则锁定。
 *
 * <p>为什么单独立一个测试类而不是往 {@code AfterSaleRuleTest} 里塞：
 * 那个类锁的是「改造前」的口径（15 天窗口、7 天无理由、状态分支），
 * 共 43 项断言，是这次改造的**兼容红线** —— 一行都不该改。
 * 新能力（商品级期限、客服通道）走本类，两边互不干扰，
 * 一旦有一侧回归，看红的是哪个类的名字就知道是「改坏了旧口径」还是「新逻辑写错了」。
 *
 * <p>覆盖：
 * <ol>
 *   <li>期限归一化（null → 默认 15、0 → 不支持退货、上限夹取、非法值判定）；</li>
 *   <li>商品级期限生效：设 7 天的商品，第 8 天即应转客服；</li>
 *   <li>「不支持退货」商品：EXPIRED 且不引导客服；</li>
 *   <li>混装订单取最严一档（最小值）；</li>
 *   <li>客服放行 → CS_APPROVED 且 eligible=true；</li>
 *   <li>放行授权过期 / 已被消费 / 被驳回 → 回到 CS_REQUIRED；</li>
 *   <li>商品「不支持无理由」时无理由剩余天数归零、且 eligibility 明确标记；</li>
 *   <li>入口形态 entryState 的 CS 分支优先级。</li>
 * </ol>
 */
@DisplayName("【2026-09-28】商品级退货期限 + 客服放行")
class AfterSaleProductRuleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0, 0);

    // ==================== 构造辅助 ====================

    private Order completedOrder(int daysAfterComplete) {
        Order o = new Order();
        o.setId(100L);
        o.setOrderNo("NO20260928001");
        o.setUserId(9L);
        o.setOrderStatus(AfterSaleRule.ORDER_STATUS_COMPLETED);
        o.setPayStatus(1);
        o.setPayAmount(new BigDecimal("200.00"));
        o.setCompleteTime(NOW.minusDays(daysAfterComplete));
        o.setUpdateTime(o.getCompleteTime());
        o.setItems(List.of(orderItem(1L, 11L)));
        return o;
    }

    private OrderItem orderItem(Long id, Long productId) {
        OrderItem it = new OrderItem();
        it.setId(id);
        it.setOrderId(100L);
        it.setProductId(productId);
        it.setProductName("商品" + productId);
        it.setPrice(new BigDecimal("100.00"));
        it.setQuantity(2);
        return it;
    }

    private Product product(Long id, Integer windowDays, Integer noReason) {
        Product p = new Product();
        p.setId(id);
        p.setName("商品" + id);
        p.setReturnWindowDays(windowDays);
        p.setReturnNoReason(noReason);
        return p;
    }

    private Map<Long, Product> products(Product... list) {
        Map<Long, Product> m = new HashMap<>();
        for (Product p : list) {
            m.put(p.getId(), p);
        }
        return m;
    }

    /** 一张「已裁定同意 + 未消费」的放行工单 */
    private ServiceTicket allowTicket(Long orderId, LocalDateTime operateTime) {
        ServiceTicket t = new ServiceTicket();
        t.setId(500L);
        t.setTicketNo("CS202609280001");
        t.setOrderId(orderId);
        t.setUserId(9L);
        t.setStatus(AfterSaleRule.TICKET_RESOLVED);
        t.setResolution(AfterSaleRule.RESOLUTION_ALLOW);
        t.setConsumed(0);
        t.setOperateTime(operateTime == null ? NOW.minusDays(1) : operateTime);
        return t;
    }

    private Map<Long, ServiceTicket> approvedOf(ServiceTicket t) {
        return t == null ? Collections.emptyMap() : Collections.singletonMap(t.getOrderId(), t);
    }

    // ==================== 1. 归一化与合法性 ====================

    @Nested
    @DisplayName("期限归一化")
    class Normalize {

        @Test
        @DisplayName("null → 默认 15（存量商品行为不变）")
        void nullBecomesDefault() {
            assertEquals(15, AfterSaleRule.normalizeReturnWindowDays(null));
            assertEquals(AfterSaleRule.DEFAULT_RETURN_WINDOW_DAYS,
                    AfterSaleRule.normalizeReturnWindowDays(null));
        }

        @Test
        @DisplayName("0 → 0（不支持退货，不得被当成「未配置」而兜底成 15）")
        void zeroStaysZero() {
            assertEquals(0, AfterSaleRule.normalizeReturnWindowDays(0));
            assertEquals(AfterSaleRule.RETURN_WINDOW_NONE, AfterSaleRule.normalizeReturnWindowDays(0));
        }

        @Test
        @DisplayName("正数原样返回；超过上限被夹到 365")
        void positiveAndClamp() {
            assertEquals(7, AfterSaleRule.normalizeReturnWindowDays(7));
            assertEquals(30, AfterSaleRule.normalizeReturnWindowDays(30));
            assertEquals(365, AfterSaleRule.normalizeReturnWindowDays(365));
            assertEquals(AfterSaleRule.MAX_RETURN_WINDOW_DAYS,
                    AfterSaleRule.normalizeReturnWindowDays(99999));
        }

        @Test
        @DisplayName("负数视为未配置（防脏数据把期限算成负天数）")
        void negativeTreatedAsUnset() {
            assertEquals(AfterSaleRule.DEFAULT_RETURN_WINDOW_DAYS,
                    AfterSaleRule.normalizeReturnWindowDays(-5));
        }

        @Test
        @DisplayName("入参合法性：0 与 1..365 合法，其余非法")
        void validity() {
            assertTrue(AfterSaleRule.isValidReturnWindowDays(0), "0 = 不支持退货，必须合法");
            assertTrue(AfterSaleRule.isValidReturnWindowDays(1));
            assertTrue(AfterSaleRule.isValidReturnWindowDays(365));
            assertFalse(AfterSaleRule.isValidReturnWindowDays(null));
            assertFalse(AfterSaleRule.isValidReturnWindowDays(-1));
            assertFalse(AfterSaleRule.isValidReturnWindowDays(366));
        }

        @Test
        @DisplayName("展示文案与期限一致")
        void windowText() {
            assertTrue(AfterSaleRule.returnWindowText(7).contains("7"));
            assertTrue(AfterSaleRule.returnWindowText(null).contains("15"), "未配置按 15 展示");
            assertEquals("不支持退货退款", AfterSaleRule.returnWindowText(0));
        }
    }

    // ==================== 2. 商品级期限生效 ====================

    @Nested
    @DisplayName("商品级期限生效")
    class WindowApplies {

        @Test
        @DisplayName("设 7 天的商品：第 6 天仍可申请，第 8 天转客服")
        void sevenDayProduct() {
            Map<Long, Product> p = products(product(11L, 7, 1));

            AfterSaleEligibility inWindow = AfterSaleRule.evaluate(
                    completedOrder(6), null, NOW, p, null);
            assertTrue(inWindow.getEligible());
            assertEquals(AfterSaleRule.CODE_ELIGIBLE, inWindow.getCode());
            assertEquals(7, inWindow.getReturnWindowDays());
            assertTrue(inWindow.getReturnWindowConfigured());

            AfterSaleEligibility over = AfterSaleRule.evaluate(
                    completedOrder(8), null, NOW, p, null);
            assertFalse(over.getEligible());
            assertEquals(AfterSaleRule.CODE_CS_REQUIRED, over.getCode(),
                    "超出商品期限应转客服，而不是彻底不可申请");
            assertTrue(over.getCsRequired());
            assertTrue(over.getHint().contains("客服"), "实际文案：" + over.getHint());
        }

        @Test
        @DisplayName("未显式配置的商品：按 15 天兜底，超期后转客服（而不是彻底不可申请）")
        void unconfiguredFallsBackTo15() {
            Map<Long, Product> p = products(product(11L, null, null));

            // 14 天：仍在 15 天兜底期限内
            AfterSaleEligibility inWindow = AfterSaleRule.evaluate(completedOrder(14), null, NOW, p, null);
            assertTrue(inWindow.getEligible());
            assertEquals(AfterSaleRule.DEFAULT_RETURN_WINDOW_DAYS, inWindow.getReturnWindowDays());
            assertFalse(inWindow.getReturnWindowConfigured(), "未配置不应被标成「已配置」");

            // 16 天：超出兜底期限 ⇒ 走客服通道（本次改造要打开的路），
            // 而不是像改造前那样直接 EXPIRED 死路。
            AfterSaleEligibility over = AfterSaleRule.evaluate(completedOrder(16), null, NOW, p, null);
            assertFalse(over.getEligible());
            assertEquals(AfterSaleRule.CODE_CS_REQUIRED, over.getCode());
            assertTrue(over.getCsRequired());
        }

        @Test
        @DisplayName("无商品上下文（旧调用路径）仍按 15 天 —— 兼容 AfterSaleRuleTest")
        void noProductContextKeepsLegacy() {
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(10), null, NOW);
            assertTrue(r.getEligible());
            assertEquals(15, r.getReturnWindowDays());
        }

        @Test
        @DisplayName("「不支持退货」(0)：EXPIRED 且不引导客服")
        void noReturnProduct() {
            Map<Long, Product> p = products(product(11L, 0, 1));
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(1), null, NOW, p, null);

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_EXPIRED, r.getCode(),
                    "明确不支持退货的商品不该把用户引到客服");
            assertFalse(r.getCsRequired());
            assertEquals(0, r.getReturnWindowDays());
        }

        @Test
        @DisplayName("混装订单取最严一档（最小值）")
        void strictestWins() {
            Order order = completedOrder(10);
            order.setItems(List.of(orderItem(1L, 11L), orderItem(2L, 12L)));
            // 商品 11 是 30 天、商品 12 是 7 天 ⇒ 整单按 7 天算，第 10 天已超期
            Map<Long, Product> p = products(product(11L, 30, 1), product(12L, 7, 1));

            AfterSaleEligibility r = AfterSaleRule.evaluate(order, null, NOW, p, null);
            assertEquals(7, r.getReturnWindowDays(), "应按最短承诺执行");
            assertEquals(AfterSaleRule.CODE_CS_REQUIRED, r.getCode());
        }

        @Test
        @DisplayName("商品查不到（已删除）不参与收紧，避免误判成 0 天")
        void missingProductDoesNotTighten() {
            Map<Long, Product> p = products(product(999L, 7, 1)); // 与订单里的 11 不匹配
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(3), null, NOW, p, null);
            assertTrue(r.getEligible());
            assertEquals(AfterSaleRule.DEFAULT_RETURN_WINDOW_DAYS, r.getReturnWindowDays());
        }
    }

    // ==================== 3. 客服放行通道 ====================

    @Nested
    @DisplayName("客服放行通道")
    class CustomerServiceChannel {

        @Test
        @DisplayName("已裁定同意（未消费）→ CS_APPROVED 且可提交")
        void approvedAllowsApply() {
            Map<Long, Product> p = products(product(11L, 7, 1));
            ServiceTicket t = allowTicket(100L, NOW.minusDays(1));

            AfterSaleEligibility r = AfterSaleRule.evaluate(
                    completedOrder(30), null, NOW, p, approvedOf(t));

            assertTrue(r.getEligible(), "客服放行后必须能提交，否则通道等于没打开");
            assertEquals(AfterSaleRule.CODE_CS_APPROVED, r.getCode());
            assertTrue(r.getCsApproved());
            assertFalse(r.getCsRequired());
            assertEquals(500L, r.getCsTicketId());
            assertNotNull(r.getCsAuthorizationDeadline());
            assertEquals("APPLY", r.getEntryState());
        }

        @Test
        @DisplayName("放行授权过期 → 回到 CS_REQUIRED（需重新联系客服）")
        void expiredAuthorization() {
            Map<Long, Product> p = products(product(11L, 7, 1));
            // 裁定时间超过 CS_AUTHORIZATION_DAYS 天
            ServiceTicket t = allowTicket(100L,
                    NOW.minusDays(AfterSaleRule.CS_AUTHORIZATION_DAYS + 1L));

            AfterSaleEligibility r = AfterSaleRule.evaluate(
                    completedOrder(30), null, NOW, p, approvedOf(t));

            assertFalse(r.getEligible());
            assertEquals(AfterSaleRule.CODE_CS_REQUIRED, r.getCode());
            assertTrue(r.getCsRequired());
            assertFalse(r.getCsApproved());
            assertTrue(r.getHint().contains("过期"), "实际文案：" + r.getHint());
            assertEquals("CS", r.getEntryState());
        }

        @Test
        @DisplayName("放行授权恰好到期的边界：仍在有效期内（闭区间）")
        void authorizationBoundary() {
            Map<Long, Product> p = products(product(11L, 7, 1));
            ServiceTicket t = allowTicket(100L, NOW.minusDays(AfterSaleRule.CS_AUTHORIZATION_DAYS));

            AfterSaleEligibility r = AfterSaleRule.evaluate(
                    completedOrder(30), null, NOW, p, approvedOf(t));

            assertTrue(r.getEligible(), "恰好到期当天不应判过期");
            assertEquals(AfterSaleRule.CODE_CS_APPROVED, r.getCode());
        }

        @Test
        @DisplayName("工单已被消费 → 不再放行（一张工单只放行一次）")
        void consumedTicketDoesNotAuthorize() {
            Map<Long, Product> p = products(product(11L, 7, 1));
            ServiceTicket t = allowTicket(100L, NOW.minusDays(1));
            t.setConsumed(1);

            AfterSaleEligibility r = AfterSaleRule.evaluate(
                    completedOrder(30), null, NOW, p, approvedOf(t));

            assertFalse(r.getCsApproved());
            assertEquals(AfterSaleRule.CODE_CS_REQUIRED, r.getCode());
        }

        @Test
        @DisplayName("工单被驳回 / 待裁定 / 已取消 → 都不构成放行")
        void nonAllowResolutionsDoNotAuthorize() {
            Map<Long, Product> p = products(product(11L, 7, 1));

            ServiceTicket rejected = allowTicket(100L, NOW.minusDays(1));
            rejected.setResolution(AfterSaleRule.RESOLUTION_REJECT);
            assertFalse(AfterSaleRule.evaluate(completedOrder(30), null, NOW, p, approvedOf(rejected))
                    .getCsApproved());

            ServiceTicket pending = allowTicket(100L, NOW.minusDays(1));
            pending.setStatus(AfterSaleRule.TICKET_PENDING);
            pending.setResolution(null);
            assertFalse(AfterSaleRule.evaluate(completedOrder(30), null, NOW, p, approvedOf(pending))
                    .getCsApproved());

            ServiceTicket cancelled = allowTicket(100L, NOW.minusDays(1));
            cancelled.setStatus(AfterSaleRule.TICKET_CANCELLED);
            assertFalse(AfterSaleRule.evaluate(completedOrder(30), null, NOW, p, approvedOf(cancelled))
                    .getCsApproved());
        }

        @Test
        @DisplayName("isUsableAuthorization：四个条件必须同时成立")
        void usableAuthorizationContract() {
            assertTrue(AfterSaleRule.isUsableAuthorization(allowTicket(100L, NOW)));
            assertFalse(AfterSaleRule.isUsableAuthorization(null));
        }

        @Test
        @DisplayName("放行授权无操作时间时不设截止日（不误伤买家）")
        void noOperateTimeMeansNoDeadline() {
            ServiceTicket t = allowTicket(100L, null);
            t.setOperateTime(null);
            assertNull(AfterSaleRule.authorizationDeadline(t));
            assertTrue(AfterSaleRule.evaluate(completedOrder(30), null, NOW,
                    products(product(11L, 7, 1)), approvedOf(t)).getEligible());
        }
    }

    // ==================== 4. 无理由政策 ====================

    @Nested
    @DisplayName("无理由退货政策")
    class NoReasonPolicy {

        @Test
        @DisplayName("商品关了无理由 → noReasonDaysLeft=0 且 returnNoReasonAllowed=false")
        void noReasonDisabledByProduct() {
            Map<Long, Product> p = products(product(11L, 15, 0));
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(1), null, NOW, p, null);

            assertTrue(r.getEligible(), "关的是无理由，不是整个售后");
            assertEquals(0, r.getNoReasonDaysLeft());
            assertEquals(Boolean.FALSE, r.getReturnNoReasonAllowed());
            assertTrue(r.getHint().contains("无理由"), "实际文案：" + r.getHint());
        }

        @Test
        @DisplayName("无理由窗口不超过商品期限（商家只给 3 天时无理由也只剩 3 天）")
        void noReasonCappedByWindow() {
            Map<Long, Product> p = products(product(11L, 3, 1));
            // 第 0 天：无理由剩余 = min(3, 7) - 0 = 3
            assertEquals(3, AfterSaleRule.evaluate(completedOrder(0), null, NOW, p, null)
                    .getNoReasonDaysLeft());
        }

        @Test
        @DisplayName("未配置无理由字段 → 视为支持（与改造前一致）")
        void defaultsToAllowed() {
            Map<Long, Product> p = products(product(11L, 15, null));
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(1), null, NOW, p, null);
            assertEquals(Boolean.TRUE, r.getReturnNoReasonAllowed());
            assertEquals(6, r.getNoReasonDaysLeft());
        }

        @Test
        @DisplayName("「不支持退货」的商品天然不支持无理由")
        void noReturnImpliesNoNoReason() {
            Map<Long, Product> p = products(product(11L, 0, 1));
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(1), null, NOW, p, null);
            assertEquals(Boolean.FALSE, r.getReturnNoReasonAllowed());
            assertEquals(0, r.getNoReasonDaysLeft());
        }
    }

    // ==================== 5. entryState ====================

    @Nested
    @DisplayName("入口形态 entryState")
    class EntryState {

        @Test
        @DisplayName("超期可申诉 → CS（前端据此渲染可点击的「联系客服」）")
        void csForOverdueWithSupport() {
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(40), null, NOW,
                    products(product(11L, 7, 1)), null);
            assertEquals("CS", r.getEntryState());
        }

        @Test
        @DisplayName("超期且不支持退货 → DISABLED（不是 CS，因为真没路）")
        void disabledForNoReturn() {
            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(40), null, NOW,
                    products(product(11L, 0, 1)), null);
            assertEquals("DISABLED", r.getEntryState());
        }

        @Test
        @DisplayName("CS 优先于 VIEW：超期且已有历史售后单时，先引导联系客服")
        void csTakesPrecedenceOverView() {
            com.ai.smartinventory.entity.AfterSale old = new com.ai.smartinventory.entity.AfterSale();
            old.setId(900L);
            old.setOrderId(100L);
            old.setStatus(AfterSaleRule.STATUS_REJECTED);
            old.setType(AfterSaleRule.TYPE_REFUND_ONLY);

            AfterSaleEligibility r = AfterSaleRule.evaluate(completedOrder(40), List.of(old), NOW,
                    products(product(11L, 7, 1)), null);

            assertTrue(r.getHasAfterSale());
            assertEquals("CS", r.getEntryState(),
                    "被驳回的历史单不该把用户困在旧单上，应引导重新联系客服");
        }

        @Test
        @DisplayName("期限内 → APPLY；超期 → CS；不支持退货 → DISABLED")
        void threeEntryStates() {
            assertEquals("APPLY", AfterSaleRule.evaluate(completedOrder(1), null, NOW,
                    products(product(11L, 15, 1)), null).getEntryState());
            // 改造后：超期但商品支持退货 ⇒ CS（可点击「联系客服」），不再是 DISABLED
            assertEquals("CS", AfterSaleRule.evaluate(completedOrder(99), null, NOW,
                    products(product(11L, 15, 1)), null).getEntryState());
            // 商品明确不支持退货 ⇒ 真的没路
            assertEquals("DISABLED", AfterSaleRule.evaluate(completedOrder(99), null, NOW,
                    products(product(11L, 0, 1)), null).getEntryState());
        }
    }

    // ==================== 6. 超期天数与「是否超期」工具 ====================

    @Nested
    @DisplayName("超期工具方法")
    class OverdueHelpers {

        @Test
        @DisplayName("isOverReturnWindow / overdueDays 口径一致")
        void helpersAgree() {
            Map<Long, Product> p = products(product(11L, 7, 1));

            assertFalse(AfterSaleRule.isOverReturnWindow(completedOrder(6), p, NOW));
            assertEquals(0, AfterSaleRule.overdueDays(completedOrder(6), p, NOW));

            assertTrue(AfterSaleRule.isOverReturnWindow(completedOrder(10), p, NOW));
            assertEquals(3, AfterSaleRule.overdueDays(completedOrder(10), p, NOW));
        }

        @Test
        @DisplayName("不支持退货的商品一律视为超期")
        void noReturnAlwaysOverdue() {
            Map<Long, Product> p = products(product(11L, 0, 1));
            assertTrue(AfterSaleRule.isOverReturnWindow(completedOrder(0), p, NOW));
        }
    }
}
