package com.ai.smartinventory.task;

import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 订单超时回收任务单元测试。
 *
 * 【为什么要为这个「定时任务」写单测】
 * 这是本项目唯一一个**无人触发、自己动库存和订单状态**的代码路径。它的缺陷不会
 * 在任何人点按钮时暴露，而是以「订单莫名其妙变成已取消」的形式在几天后出现，
 * 且极难复现（要等 15 分钟 + 等一个扫描周期）。因此必须把三条契约钉死：
 *
 *   1. 【只碰该碰的】判定条件必须是「待付款 + 未付款 + 创建时间早于截止点」，
 *      截止点 = 当前时间 - 配置的超时分钟数。把边界算错（例如少减一次、
 *      或把「超时未付款」写成「所有未付款」）就会误伤刚下的单。
 *   2. 【取消原因必须打标】走后端 cancelOrder(order, AUTO_TIMEOUT)，
 *      没有这个标记，前端就无法把「系统超时取消」和「用户自己取消」区分开，
 *      会把用户亲手点的取消也提示成「系统帮你取消了」。
 *   3. 【单笔失败不拖累整批 / 并发不误伤】某单取消失败只记录并跳过；
 *      用户在扫描瞬间付款（cancelOrder 未拿到取消权）不得计入取消数、更不得真取消。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("订单超时回收任务")
class OrderTimeoutTaskTest {

    private static final int TIMEOUT_MINUTES = 15;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderTimeoutTask task;

    @BeforeEach
    void setUp() {
        // @Value 注入在纯 Mockito 环境下不生效，这里显式赋值，
        // 使「超时时长」在测试里是一个确定的 15 分钟。
        ReflectionTestUtils.setField(task, "unpaidTimeoutMinutes", TIMEOUT_MINUTES);
    }

    private Order order(long id, String no) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNo(no);
        o.setOrderStatus(0); // 待付款
        o.setPayStatus(0);   // 未付款
        o.setCreateTime(LocalDateTime.now().minusMinutes(30));
        return o;
    }

    /** 模拟 cancelOrder 成功：把内存对象改成「已取消」，与真实实现一致 */
    private void stubCancelSucceeds() {
        doAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setOrderStatus(4);
            o.setCancelReason(OrderService.CANCEL_REASON_TIMEOUT);
            return null;
        }).when(orderService).cancelOrder(any(Order.class), eq(OrderService.CANCEL_REASON_TIMEOUT));
    }

    @Test
    @DisplayName("截止点 = 当前时间 - 超时分钟数，且只把待付款单交给服务层判定")
    void passesCorrectDeadline() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0, 0);
        when(orderService.listTimeoutUnpaidOrders(any())).thenReturn(Collections.emptyList());

        int canceled = task.cancelTimeoutOrders(now);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderService).listTimeoutUnpaidOrders(captor.capture());
        assertEquals(LocalDateTime.of(2026, 10, 9, 11, 45, 0), captor.getValue(),
                "截止点必须是「当前时间 - 15 分钟」，算错就会误伤刚下的单或漏掉真正的超时单");
        assertEquals(0, canceled);
    }

    @Test
    @DisplayName("扫描到超时单 → 逐单以 AUTO_TIMEOUT 原因取消，返回取消数")
    void cancelsEachTimedOutOrder() {
        Order o1 = order(1L, "NO1");
        Order o2 = order(2L, "NO2");
        when(orderService.listTimeoutUnpaidOrders(any())).thenReturn(List.of(o1, o2));
        stubCancelSucceeds();

        int canceled = task.cancelTimeoutOrders(LocalDateTime.now());

        assertEquals(2, canceled);
        // 取消原因必须是 AUTO_TIMEOUT：前端靠它渲染「超时未付款，系统已自动取消」
        verify(orderService).cancelOrder(o1, OrderService.CANCEL_REASON_TIMEOUT);
        verify(orderService).cancelOrder(o2, OrderService.CANCEL_REASON_TIMEOUT);
    }

    @Test
    @DisplayName("没有超时单 → 不产生任何取消动作")
    void noTimeoutOrders() {
        when(orderService.listTimeoutUnpaidOrders(any())).thenReturn(Collections.emptyList());

        assertEquals(0, task.cancelTimeoutOrders(LocalDateTime.now()));
        verify(orderService, never()).cancelOrder(any(Order.class), anyString());
    }

    @Test
    @DisplayName("用户恰好在扫描瞬间付款 → 未拿到取消权，不计入取消数")
    void skipsWhenOrderNoLongerCancellable() {
        Order o = order(1L, "NO1");
        when(orderService.listTimeoutUnpaidOrders(any())).thenReturn(List.of(o));
        // 真实实现在 CAS 失败时直接 return，不改内存对象的状态；
        // 这里用「什么都不做」模拟该分支。
        doNothing().when(orderService).cancelOrder(any(Order.class), anyString());

        int canceled = task.cancelTimeoutOrders(LocalDateTime.now());

        assertEquals(0, canceled, "没拿到取消权就不能算作已取消");
        assertEquals(0, o.getOrderStatus(), "订单状态不应被本任务改写");
    }

    @Test
    @DisplayName("单笔取消抛异常 → 跳过该单，其余订单照常处理")
    void oneFailureDoesNotAbortBatch() {
        Order bad = order(1L, "BAD");
        Order good = order(2L, "GOOD");
        when(orderService.listTimeoutUnpaidOrders(any())).thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("模拟数据库抖动"))
                .when(orderService).cancelOrder(bad, OrderService.CANCEL_REASON_TIMEOUT);
        doAnswer(inv -> {
            ((Order) inv.getArgument(0)).setOrderStatus(4);
            return null;
        }).when(orderService).cancelOrder(good, OrderService.CANCEL_REASON_TIMEOUT);

        int canceled = task.cancelTimeoutOrders(LocalDateTime.now());

        assertEquals(1, canceled, "失败的那单不计入，但后面的单必须继续处理");
        verify(orderService).cancelOrder(good, OrderService.CANCEL_REASON_TIMEOUT);
    }

    @Test
    @DisplayName("定时入口不向外抛异常（避免调度线程留下无上下文的孤立堆栈）")
    void scheduledEntrySwallowsException() {
        when(orderService.listTimeoutUnpaidOrders(any()))
                .thenThrow(new RuntimeException("模拟查询失败"));

        assertDoesNotThrow(() -> task.scanAndCancelTimeoutOrders());
    }
}
