package com.ai.smartinventory.service;

import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.entity.Order;
import com.baomidou.mybatisplus.extension.service.IService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderService extends IService<Order> {

    /**
     * 取消原因：超时未付款，由定时任务自动取消。
     *
     * <p>前端据此把「已取消」渲染成「因超时未付款，系统已自动取消」，
     * 与用户/商家主动取消区分开（后者不会显示这句话）。
     */
    String CANCEL_REASON_TIMEOUT = "AUTO_TIMEOUT";

    /** 取消原因：订单所属用户主动取消。 */
    String CANCEL_REASON_USER = "USER";

    /** 取消原因：商家关闭订单。 */
    String CANCEL_REASON_MERCHANT = "MERCHANT";

    PageResult<Order> getOrderPage(Long userId, Integer page, Integer size, Integer status);
    
    Order getOrderById(Long id);
    
    Order getOrderByIdWithPermission(Long id, Long userId, String role);
    
    List<Order> getUserOrders(Long userId, Integer status);
    
    boolean isOrderOwnedByUser(Long orderId, Long userId);
    
    /**
     * 判断订单是否包含指定商家的商品（多商户隔离用）。
     * 订单表无 merchant_id 字段，需经 order_item → product.merchant_id 反查。
     */
    boolean isOrderOwnedByMerchant(Long orderId, Long merchantId);
    
    /**
     * 创建订单（含订单明细、购物车清理）。
     *
     * 【库存扣减时机·2026-09-23】下单**不再**扣库存（原先下单即扣，用户一提交订单
     * 库存立刻减少，与业务要求不符）。现按订单状态扣减：
     * 商家确认发货 或 订单签收 时才调用 {@link #deductStockForOrder} 扣减，
     * 且同一订单只扣一次（幂等）。
     *
     * 【安全修复·P0-1】金额不再由调用方传入。商品名 / 图片 / 单价 / 总价
     * 一律由本方法按 productId 查库计算，客户端无法篡改。
     */
    Order createOrderWithItems(Long userId, List<OrderItemDTO> items,
                               String shippingName, String shippingPhone,
                               String shippingAddress, String remark);

    /**
     * 扣减本订单商品库存 —— 在「商家确认发货」或「订单签收」时触发。
     *
     * 幂等：以 inventory_log 中本订单的出库流水为凭，同一订单只扣一次，
     * 重复调用（先签收后补录发货、多次点击等）不会重复扣减。
     * 库存不足时抛出业务异常并回滚本次调用，不产生半扣状态。
     */
    void deductStockForOrder(Long orderId);

    /**
     * 取消订单；仅当本订单已扣减过库存（已发货/签收）时才回补，
     * 未扣过（未发货即取消）不得回补，否则库存会凭空增加。
     *
     * <p>保留此重载是为了不破坏既有调用方：等价于
     * {@code cancelOrder(order, CANCEL_REASON_USER)}。
     */
    void cancelOrder(Order order);

    /**
     * 取消订单并记录取消原因（{@link #CANCEL_REASON_TIMEOUT} /
     * {@link #CANCEL_REASON_USER} / {@link #CANCEL_REASON_MERCHANT}）。
     *
     * <p>除多写一个 cancel_reason 外，行为与 {@link #cancelOrder(Order)} 完全一致：
     * 仍是「条件更新充当并发闸门 → 只有确实扣过库存的订单才回补」。
     *
     * <p><b>取消原因同时决定闸门的宽窄</b>（S-5 修复）：
     * <ul>
     *   <li>{@link #CANCEL_REASON_TIMEOUT} —— 条件收紧为
     *       <b>「order_status = 0 且未付款(0/NULL)」</b>。超时回收是「扫描 → 逐单取消」
     *       两步，中间若用户刚好付款（0 → 1），用原来宽口径的 {@code IN (0,1,2)}
     *       依然命中，就会把**刚付过款的单**标成已取消；商家抢先发货（0 → 2）同理。</li>
     *   <li>用户 / 商家主动取消 —— 保持 {@code IN (0,1,2)}：他们本来就允许取消
     *       已付款待发货、待收货的订单，收紧会直接砍掉正常功能。</li>
     * </ul>
     * 这两条路径与 {@link #markPaid(Long)} 共同关闭了「付款 ↔ 取消」的双向竞态。
     *
     * @param reason 取消原因；传 null 时按「用户取消」处理，保证列上的语义不空转
     */
    void cancelOrder(Order order, String reason);

    /**
     * 查询「已下单但超过 {@code deadline} 仍未付款」的订单，供超时自动取消使用。
     *
     * <p>条件：order_status = 0（待付款）且 pay_status = 0（未付款）
     * 且 create_time &lt; deadline。**只读**，不做任何状态变更 ——
     * 真正的取消动作由调用方逐单走 {@link #cancelOrder(Order, String)}，
     * 这样每单一个独立事务：某一单取消失败（例如并发被用户抢先付款）
     * 不会连带回滚其他订单。
     *
     * @param deadline 截止时刻，早于该时刻创建的待付款订单将被返回
     */
    List<Order> listTimeoutUnpaidOrders(LocalDateTime deadline);

    /**
     * 【S-3/S-4 修复】原子推进订单状态，并在需要时扣减库存。
     *
     * <p>以「条件更新」充当并发闸门：仅当订单当前状态属于 {@code allowedFrom} 时，
     * 当前线程才把状态推进到 {@code toStatus}；并发请求（双击「发货」、发货与签收
     * 同时发生）只有一个能成功，另一个得到 false —— 从根本上杜绝「同一订单被扣两次库存」。
     *
     * <p>状态推进与库存扣减在**同一事务**内：库存不足会整体回滚，订单状态保持原样，
     * 不会出现「状态已变但库存没扣」的半状态。
     *
     * @param orderId     订单 id
     * @param toStatus    目标状态（2 = 发货 / 3 = 完成）
     * @param allowedFrom 允许的当前状态；当前状态不在此列则不做任何变更
     * @return true 表示本次由本线程成功推进；false 表示状态已变化或不允许
     */
    boolean advanceStatus(Long orderId, int toStatus, Integer... allowedFrom);

    /**
     * 【S-5 修复】付款：以一条条件更新把订单从「待付款(0)」原子推进到「待发货(1)」，
     * 同时落 {@code pay_status = 1} 与 {@code pay_time}。
     *
     * <p><b>为什么不能用 {@code updateById(order)}：</b>原实现是「读订单 → 在内存里判
     * {@code orderStatus == 0} → 整实体写回」，判定与写入之间没有任何数据库闸门。
     * 两个方向都会出事：
     * <ul>
     *   <li><b>已取消被复活</b>：超时任务先把状态改成 4，随后这次付款无条件写回 1，
     *       得到一张「显示已付款、却从未扣过库存」的幽灵单，且 cancel_time /
     *       cancel_reason 仍留在库里；</li>
     *   <li><b>已付款被取消</b>：反过来，付款先落地（状态 1），
     *       取消方的 {@code IN (0,1,2)} 依然命中，付过款的单被标成已取消。</li>
     * </ul>
     * 现把「当前必须还是 0」直接写进 WHERE，两条竞态一并关闭；
     * 与 {@link #advanceStatus} 同属「条件更新即并发闸门」这一范式。
     *
     * <p>Controller 里保留的内存判断只用于给出可读的错误文案，**不是**并发防线。
     *
     * @param orderId 订单 id
     * @return true 表示本次由本线程完成付款；false 表示订单已不在待付款状态
     *         （已被取消 / 已发货 / 已完成 / 重复付款），调用方须据此拒绝而不是静默成功
     */
    boolean markPaid(Long orderId);

    /**
     * 下单明细入参。
     *
     * 【安全修复·P0-1】此前本记录含 productName / productImage / price 三个字段，
     * 均由客户端提交并**直接落库**，服务端从不与 product 表比对 ——
     * 攻击者可把 price 改成 0.01 实现「1 分钱下单」。
     *
     * 现只保留 productId + quantity：商品名 / 图片 / 单价一律由服务端
     * 按 productId 查库回填，客户端无法左右金额。
     */
    record OrderItemDTO(Long productId, Integer quantity) {}
}