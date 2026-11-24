package com.ai.smartinventory.task;

import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.service.OrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单超时回收：用户下单后超过约定时长仍未付款，由系统自动取消。
 *
 * 【要解决的问题】
 * 用户提交订单后（此时不扣库存、订单停在「待付款(0)」）若一直不付款，该订单会
 * 永久挂在商家列表里：商家看到一个「未付款却带发货按钮」的单子，无从判断该不该发；
 * 用户自己也会在「待付款」里堆一列早就不要了的僵尸单。
 *
 * 【为什么用轮询而不是延迟队列 / MQ】
 * 本项目的运行环境不保证 Redis 可用（限流已为此降级），更没装 MQ；引入
 * 延迟队列等于给「订单超时」这个非核心链路绑上一套中间件依赖。轮询一轮只跑
 * 一条带索引的查询（order_status + pay_status + create_time），代价可忽略。
 *
 * 【为什么不写「定时扫全表」的暴力实现】
 * 查询在 {@code OrderServiceImpl.listTimeoutUnpaidOrders} 里，条件已收敛为
 * 「待付款(0) + 未付款(0) + create_time < deadline」，配合 init.sql 的
 * {@code idx_status}，扫描量随超时单数量走而不是随订单总量走。
 *
 * 【并发正确性】真正的取消动作逐单落到 {@code cancelOrder(order, AUTO_TIMEOUT)}。
 * 该方法识别出 AUTO_TIMEOUT 后会把闸门收紧为「order_status = 0 且未付款(0/NULL)」，
 * 而不是用户/商家主动取消用的 {@code IN (0,1,2)} —— 因为超时回收是「扫描 → 逐单取消」
 * 两步，中间存在窗口：
 *   · 用户恰好在窗口内完成付款（0 → 1）→ 条件不成立 → 放弃取消，不会误杀已付款订单；
 *   · 商家抢先发货（0 → 2）→ 同样不满足「仍是待付款」→ 放弃取消；
 *   · 用户手动取消、商家同时关闭 → 只有一个能拿到取消权，不会重复回补库存。
 * 付款侧由 {@code markPaid} 的 {@code WHERE order_status = 0} 对称守住。
 * 每单一个独立事务，单笔失败不会连带回滚其余订单。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "order.timeout-cancel.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutTask {

    @Resource
    private OrderService orderService;

    /**
     * 未付款超时时长（分钟）。与 OrderServiceImpl 回填 payDeadline 用的是
     * 同一个配置键，保证「前端倒计时归零」与「订单被取消」两个时刻一致。
     * 字段初始值仅供无 Spring 容器的单测使用，生产以 yml 为准。
     */
    @Value("${order.timeout-cancel.unpaid-timeout-minutes:15}")
    private int unpaidTimeoutMinutes = 15;

    /**
     * 定时入口。
     *
     * <p>用 fixedDelay（上一轮跑完再等一个间隔）而不是 fixedRate：固定频率在
     * 任务耗时超过间隔时会不断堆积排队，而订单回收早跑几秒晚跑几秒毫无影响。
     * initialDelay 留出启动缓冲，避免应用刚起来、连接池还没热就开始扫库。
     */
    @Scheduled(
            initialDelayString = "${order.timeout-cancel.initial-delay-ms:30000}",
            fixedDelayString = "${order.timeout-cancel.scan-interval-ms:60000}")
    public void scanAndCancelTimeoutOrders() {
        try {
            cancelTimeoutOrders(LocalDateTime.now());
        } catch (Exception e) {
            // 后台任务不能把异常抛出去：一次异常若无捕获，会被调度线程记录后
            // 继续按周期执行，日志里就是一条孤立的堆栈，缺少「这一轮在做什么」的上下文。
            log.error("订单超时回收任务执行失败", e);
        }
    }

    /**
     * 执行一轮超时回收，返回本轮**确实被取消**的订单数。
     *
     * <p>可见性为包级方法、时间由入参传入（而非内部取 now），是为了让单测能
     * 直接把「当前时间」推到超时之后来驱动，不必真的 sleep 15 分钟。
     */
    int cancelTimeoutOrders(LocalDateTime now) {
        LocalDateTime deadline = now.minusMinutes(unpaidTimeoutMinutes);
        List<Order> timeouts = orderService.listTimeoutUnpaidOrders(deadline);
        if (timeouts == null || timeouts.isEmpty()) {
            return 0;
        }

        int canceled = 0;
        for (Order order : timeouts) {
            try {
                orderService.cancelOrder(order, OrderService.CANCEL_REASON_TIMEOUT);
                // cancelOrder 内部若因并发（用户刚好付款）没拿到取消权，
                // 只改不动库内状态 —— 内存对象的 orderStatus 仍是 0，据此判定真实结果。
                if (order.getOrderStatus() != null && order.getOrderStatus() == 4) {
                    canceled++;
                    log.info("超时未付款自动取消订单: orderNo={}, createTime={}, deadline={}",
                            order.getOrderNo(), order.getCreateTime(), deadline);
                } else {
                    log.info("订单在超时回收前状态已变化，跳过取消: orderNo={}", order.getOrderNo());
                }
            } catch (Exception e) {
                // 单笔失败不影响其余订单：记录后继续，下一轮会重新捞到这一单。
                log.warn("超时自动取消失败，已跳过该订单: orderId={}, orderNo={}",
                        order.getId(), order.getOrderNo(), e);
            }
        }
        if (canceled > 0) {
            log.info("本轮订单超时回收完成: 扫描到 {} 单，实际取消 {} 单", timeouts.size(), canceled);
        }
        return canceled;
    }
}
