package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.UserBehaviorRecorder;
import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.mapper.InventoryLogMapper;
import com.ai.smartinventory.mapper.OrderItemMapper;
import com.ai.smartinventory.mapper.OrderMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.mapper.UserMapper;
import com.ai.smartinventory.service.CartService;
import com.ai.smartinventory.service.InventoryService;
import com.ai.smartinventory.service.AfterSaleService;
import com.ai.smartinventory.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {
    
    @Resource
    private OrderMapper orderMapper;
    
    @Resource
    private OrderItemMapper orderItemMapper;
    
    @Resource
    private UserMapper userMapper;
    
    @Resource
    private InventoryService inventoryService;
    
    @Resource
    private CartService cartService;

    /** R18：用户行为埋点（下单 → purchase）。注意它内部走事务 afterCommit，不会污染本方法的事务 */
    @Resource
    private UserBehaviorRecorder userBehaviorRecorder;

    @Resource
    private ProductMapper productMapper;

    @Resource
    private InventoryLogMapper inventoryLogMapper;

    /**
     * 【确认收货后申请售后·入口回填】
     * 订单列表要显示「申请售后 / 查看售后 / 售后已过期」，判定口径必须与
     * 售后提交接口完全一致，否则会出现「按钮能点但提交被拒」。
     * 这里一次性批量判定（内部只多 1 条 SQL），把结果挂到每个 Order 上。
     */
    @Resource
    private AfterSaleService afterSaleService;

    /**
     * 未付款自动取消的超时时长（分钟），与定时任务的判定口径共用同一份配置。
     *
     * 【为什么写在这个字段上】两处都依赖它：
     *   1) 定时任务按 create_time + 本值 筛选「超时仍未付款」的订单；
     *   2) 订单列表按 create_time + 本值 回填 payDeadline，供前端渲染倒计时。
     * 若各写各的常量，就会出现「倒计时还没走完，订单已被取消」的错位。
     * 初始值 15 只是单测（无 Spring 容器）下的兜底，生产以 yml 为准。
     */
    @Value("${order.timeout-cancel.unpaid-timeout-minutes:15}")
    private int unpaidTimeoutMinutes = 15;
    
    @Override
    public PageResult<Order> getOrderPage(Long userId, Integer page, Integer size, Integer status) {
        // 【P1-3】统一夹取页码与 size 上限
        Page<Order> pageParam = new Page<>(PageBounds.page(page), PageBounds.size(size));
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        
        if (userId != null) {
            wrapper.eq(Order::getUserId, userId);
        }
        
        // 【多商户隔离】商家视角只应看到「包含自己商品」的订单。
        // 原实现商家与管理员一样传 userId = null，实际能看到全平台所有订单（越权）。
        // 这里通过 order_item → product.merchant_id 反查，限定订单范围。
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId != null) {
            List<Long> orderIds = orderMapper.selectOrderIdsByMerchantId(merchantId);
            if (orderIds.isEmpty()) {
                // 该商家尚无任何订单，直接返回空页，避免生成 IN () 非法 SQL
                // 【P1-3】空页也走夹取后的口径，避免响应回吐未经限制的原始 size。
                return new PageResult<>(0L, Collections.emptyList(),
                        (long) PageBounds.page(page), (long) PageBounds.size(size));
            }
            wrapper.in(Order::getId, orderIds);
        }
        
        if (status != null && status >= 0) {
            wrapper.eq(Order::getOrderStatus, status);
        }
        
        wrapper.orderByDesc(Order::getCreateTime);
        
        Page<Order> result = this.page(pageParam, wrapper);
        
        // 批量填充username和订单明细
        List<Order> orders = result.getRecords();
        if (!orders.isEmpty()) {
            // 1. 批量查询用户名
            Set<Long> userIds = orders.stream()
                    .map(Order::getUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!userIds.isEmpty()) {
                List<User> users = userMapper.selectBatchIds(userIds);
                Map<Long, String> userMap = users.stream()
                        .collect(Collectors.toMap(User::getId, u -> u.getUsername() != null ? u.getUsername() : u.getNickname()));
                for (Order order : orders) {
                    order.setUsername(userMap.get(order.getUserId()));
                }
            }
            
            // 2. 批量查询订单明细
            fillItems(orders);

            // 2.1 待付款订单回填「付款截止时间」，供前端渲染「剩余 xx:xx 自动取消」
            fillPayDeadline(orders);

            // 3. 售后入口状态批量回填（一次判定，前端据此渲染「申请售后 / 查看售后 / 已过期」）
            Map<Long, AfterSaleEligibility> eligibilityMap = afterSaleService.evaluateBatch(orders);
            for (Order order : orders) {
                order.setAfterSale(eligibilityMap.get(order.getId()));
            }
        }
        
        return new PageResult<>(result.getTotal(), orders, result.getCurrent(), result.getSize());
    }
    
    @Override
    public Order getOrderById(Long id) {
        return this.getOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, id));
    }
    
    @Override
    public Order getOrderByIdWithPermission(Long id, Long userId, String role) {
        Order order = getOrderById(id);
        if (order == null) {
            return null;
        }
        
        if ("admin".equals(role)) {
            fillItems(Collections.singletonList(order));
            fillPayDeadline(Collections.singletonList(order));
            return order;
        }
        
        // 【多商户隔离】商家只能访问「订单中存在自己商品」的订单。
        // 原实现商家直接 return order，等于任意商家都能凭订单 ID 查看
        // 别人店铺订单的收货人姓名/电话/地址，是明确的个人信息泄露。
        if ("merchant".equals(role)) {
            Long merchantId = MerchantContext.currentMerchantId();
            if (merchantId == null) {
                return null;
            }
            if (orderMapper.countOrderOwnedByMerchant(id, merchantId) > 0) {
                fillItems(Collections.singletonList(order));
            fillPayDeadline(Collections.singletonList(order));
                return order;
            }
            return null;
        }
        
        if (order.getUserId().equals(userId)) {
            fillItems(Collections.singletonList(order));
            fillPayDeadline(Collections.singletonList(order));
            return order;
        }
        
        return null;
    }

    /**
     * 【P1-6】批量回填订单明细（{@code order.items}），避免 N+1。
     *
     * <p>getOrderPage 与 getOrderByIdWithPermission 共用同一份口径：
     * 后者此前不回填 items，导致售后申请页 {@code order.items} 为空、无法按商品勾选。
     * 这里用一次 {@code in} 批量查询 + 内存分组，与分页方法原实现逐字等价；
     * 空集合提前 return（{@code in()} 收到空集会拼出非法 SQL）。</p>
     */
    private void fillItems(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        Set<Long> orderIds = orders.stream()
                .map(Order::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (orderIds.isEmpty()) {
            return;
        }
        List<OrderItem> allItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderId, orderIds));
        Map<Long, List<OrderItem>> itemMap = allItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        for (Order order : orders) {
            order.setItems(itemMap.getOrDefault(order.getId(), Collections.emptyList()));
        }
    }
    
    @Override
    public List<Order> getUserOrders(Long userId, Integer status) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId);
        
        if (status != null && status >= 0) {
            wrapper.eq(Order::getOrderStatus, status);
        }
        
        wrapper.orderByDesc(Order::getCreateTime);
        return this.list(wrapper);
    }
    
    @Override
    public boolean isOrderOwnedByUser(Long orderId, Long userId) {
        Order order = getOrderById(orderId);
        return order != null && order.getUserId().equals(userId);
    }

    @Override
    public boolean isOrderOwnedByMerchant(Long orderId, Long merchantId) {
        if (orderId == null || merchantId == null) {
            return false;
        }
        return orderMapper.countOrderOwnedByMerchant(orderId, merchantId) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order createOrderWithItems(Long userId, List<OrderItemDTO> items,
                                      String shippingName, String shippingPhone,
                                      String shippingAddress, String remark) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(400, "订单商品不能为空");
        }

        // 0. 【安全修复·P0-1】先按 productId 查库，把商品名/图片/单价/小计全部算出来。
        //    此前这些值直接取自客户端（OrderItemDTO 里的 price/productName），
        //    服务端只校验库存、从不比对价格 —— 攻击者传 price=0.01 即可「1 分钱下单」。
        //    现在金额的唯一来源是 product 表，客户端只能决定「买什么、买几件」。
        List<OrderItem> resolvedItems = new ArrayList<>(items.size());
        BigDecimal computedTotal = BigDecimal.ZERO;

        for (OrderItemDTO item : items) {
            if (item.productId() == null) {
                throw new BusinessException(400, "商品 ID 不能为空");
            }
            if (item.quantity() == null || item.quantity() <= 0) {
                throw new BusinessException(400, "商品数量必须大于 0");
            }

            Product product = productMapper.selectById(item.productId());
            if (product == null || (product.getDeleted() != null && product.getDeleted() == 1)) {
                throw new BusinessException(400, "商品不存在或已下架，请刷新后重试");
            }
            if (product.getPrice() == null) {
                throw new BusinessException(500, "商品「" + product.getName() + "」价格异常，无法下单");
            }

            // 可售性校验：下单不扣库存，但要把明显缺货挡在这里。
            // 以 product.stock 为展示口径（权威库存在 inventory 表，发货时才原子扣减）。
            int available = product.getStock() != null ? product.getStock() : 0;
            if (available < item.quantity()) {
                throw new BusinessException(400, "商品「" + product.getName() + "」库存不足，当前可售 "
                        + Math.max(available, 0) + " 件");
            }

            BigDecimal unitPrice = product.getPrice();
            BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
            computedTotal = computedTotal.add(amount);

            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());     // ← 服务端回填，非客户端
            orderItem.setProductImage(product.getImage());   // ← 服务端回填，非客户端
            orderItem.setPrice(unitPrice);                   // ← 服务端回填，非客户端
            orderItem.setQuantity(item.quantity());
            orderItem.setAmount(amount);                     // ← 服务端计算，非客户端
            resolvedItems.add(orderItem);
        }

        // 1. 创建订单（金额全部来自上面的服务端计算）
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setTotalAmount(computedTotal);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayAmount(computedTotal);
        order.setOrderStatus(0);
        order.setPayStatus(0);
        order.setShippingName(shippingName);
        order.setShippingPhone(shippingPhone);
        order.setShippingAddress(shippingAddress);
        order.setRemark(remark);
        order.setDeleted(0);

        if (!this.save(order)) {
            throw new BusinessException(500, "订单创建失败");
        }

        // 2. 落订单明细（下单**不扣**库存，只做可售性校验）
        // 【库存扣减时机修复·2026-09-23】原实现在下单时直接 reduceStock ——
        // 用户一提交订单库存立刻减少，与业务要求不符。现按订单状态扣减：
        // 「商家确认发货」或「订单签收」时才调用 deductStockForOrder（幂等）。
        // 这里仅做只读的可售性校验，尽早拦住明显超量/缺货的下单；
        // 真正的原子扣减（条件 UPDATE 防超卖）保留在发货/签收环节。
        // 明细的 productName/productImage/price/amount 全部来自步骤 0 的服务端解析结果
        // （resolvedItems），客户端无法再左右金额；可售性也已在步骤 0 校验完毕。
        for (OrderItem orderItem : resolvedItems) {
            orderItem.setOrderId(order.getId());
            if (orderItemMapper.insert(orderItem) <= 0) {
                throw new BusinessException(500, "订单明细创建失败: " + orderItem.getProductName());
            }
        }

        // 【R18】埋点：下单成功记 purchase 行为（协同过滤推荐的**唯一**数据来源）。
        // 位置放在明细落库之后 —— 只有明细确实写进去了，才算一次真实购买。
        // record() 在事务内注册 afterCommit，所以既不会污染订单事务，
        // 也不会留下「订单回滚了、行为却已落库」的脏数据。
        for (OrderItem orderItem : resolvedItems) {
            userBehaviorRecorder.record(userId, UserBehaviorRecorder.TYPE_PURCHASE, orderItem.getProductId());
        }

        // 3. 清理购物车中**本次下单**的商品
        // 【数据丢失修复·2026-09-22】原先这里是 cartService.clearCart(userId) 整单清空：
        // 「勾选部分商品结算」时，未勾选、并未购买的商品会被一并软删 ——
        // 表现为“下单后再打开购物车，没买的东西全没了”（静默数据丢失）。
        // 现改为按本单明细的 productId 精确移除，未购买商品保留（淘宝/京东语义），
        // 且与订单创建同事务：要么一起成功、要么一起回滚。
        List<Long> orderedProductIds = resolvedItems.stream()
                .map(OrderItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        cartService.removeByProductIds(userId, orderedProductIds);

        log.info("订单创建成功: orderNo={}, userId={}, items={}, totalAmount={}",
            order.getOrderNo(), userId, resolvedItems.size(), computedTotal);

        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductStockForOrder(Long orderId) {
        if (orderId == null) {
            return;
        }
        // 幂等保护：inventory_log 里已有本订单的出库流水（type=1 出库、orderType=order）
        // 即代表已扣过（发货时扣过 / 签收时扣过），重复触发直接跳过。
        Long deducted = inventoryLogMapper.selectCount(new LambdaQueryWrapper<InventoryLog>()
                .eq(InventoryLog::getOrderId, orderId)
                .eq(InventoryLog::getOrderType, "order")
                .eq(InventoryLog::getType, 1));
        if (deducted != null && deducted > 0) {
            return;
        }

        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        if (items == null || items.isEmpty()) {
            return;
        }

        for (OrderItem item : items) {
            // reduceStock 在库存不足时抛业务异常，@Transactional 整体回滚，
            // 不会出现「部分商品扣了、部分没扣」的半状态。
            try {
                inventoryService.reduceStock(item.getProductId(), item.getQuantity(), "order", orderId);
            } catch (BusinessException e) {
                throw new BusinessException(e.getCode(),
                        "商品「" + item.getProductName() + "」" + e.getMessage());
            }
            // 【2026-10-10】销量与库存同事务、同次数变化。
            // 本方法由 inventory_log 幂等闸门保护（重复触发直接 return），
            // 所以销量也天然只会被 + 一次，与库存扣减严格 1:1 对应。
            // 之前 product.sales 全项目无人写入，导致商品管理/详情的「销量」恒为 0，
            // 热门榜的 ORDER BY sales DESC 排序依据全 0 等于没排。
            productMapper.increaseSales(item.getProductId(), item.getQuantity());
        }
        log.info("订单扣减库存完成（发货/签收触发）: orderId={}, items={}", orderId, items.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean advanceStatus(Long orderId, int toStatus, Integer... allowedFrom) {
        if (orderId == null || allowedFrom == null || allowedFrom.length == 0) {
            return false;
        }
        // 【S-4】原子占位（CAS）：把「当前状态 ∈ allowedFrom」作为 WHERE 条件，
        // 并发场景下只有一个线程能把状态改到 toStatus，其余 affected=0 直接返回 false。
        LambdaUpdateWrapper<Order> uw = new LambdaUpdateWrapper<>();
        uw.eq(Order::getId, orderId)
          .in(Order::getOrderStatus, (Object[]) allowedFrom)
          .set(Order::getOrderStatus, toStatus);
        if (toStatus == 2) {
            uw.set(Order::getShipTime, LocalDateTime.now());
        } else if (toStatus == 3) {
            uw.set(Order::getCompleteTime, LocalDateTime.now());
        }
        boolean moved = this.update(uw);
        if (!moved) {
            return false;
        }
        // 与状态推进处于同一事务：库存不足会抛异常并整体回滚，订单状态回到原值。
        if (toStatus == 2 || toStatus == 3) {
            deductStockForOrder(orderId);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markPaid(Long orderId) {
        if (orderId == null) {
            return false;
        }
        // 【S-5】条件更新即并发闸门：把「当前必须仍是待付款(0)」写进 WHERE。
        // 这样已取消(4)、已发货(2)、已完成(3)、已付款(1)的单子都拿不到这次写入，
        // 权限与状态校验不再依赖调用方「先读后写」的那段内存判断。
        // 三个字段放进同一条 UPDATE：状态、付款标记与付款时间要么一起生效，要么都不生效，
        // 不会出现「pay_status=1 但 order_status 还是 0」的半状态。
        return this.update(new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getOrderStatus, 0)
                .set(Order::getOrderStatus, 1)
                .set(Order::getPayStatus, 1)
                .set(Order::getPayTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Order order) {
        // 保持旧签名可用：语义 = 用户主动取消
        cancelOrder(order, CANCEL_REASON_USER);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Order order, String reason) {
        // 【S-5 修复·超时取消收紧】取消原因同时决定闸门的宽窄：
        //   · AUTO_TIMEOUT ⇒ 必须「仍是待付款(0) 且仍未付款(0/NULL)」
        //     —— 超时回收是「扫描 → 逐单取消」两步，中间用户完成付款（0→1）时，
        //     旧口径 IN (0,1,2) 依然命中，会把刚付过款的单标成已取消；
        //     商家抢先发货（0→2）同理，不该被超时任务关掉。
        //   · USER / MERCHANT ⇒ 保持 IN (0,1,2)，因为主动取消本就允许取消
        //     已付款待发货、待收货的订单，收紧会砍掉正常功能。
        // 付款侧由 markPaid 的 WHERE order_status = 0 对称守住，双向竞态一并关闭。
        boolean requireUnpaidOnly = CANCEL_REASON_TIMEOUT.equals(reason);

        // 【S-4】原子占位：仅当订单仍处于「本次允许取消」的状态时才由本线程继续；
        // 并发取消（双击、商家与用户同时操作、用户付款与超时任务同时发生）
        // 只有一个能成功，杜绝「同一订单被重复回补库存」。
        LambdaUpdateWrapper<Order> uw = new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, order.getId());
        if (requireUnpaidOnly) {
            uw.eq(Order::getOrderStatus, 0)
              .and(w -> w.eq(Order::getPayStatus, 0).or().isNull(Order::getPayStatus));
        } else {
            uw.in(Order::getOrderStatus, 0, 1, 2);
        }
        uw.set(Order::getOrderStatus, 4)
          .set(Order::getCancelTime, LocalDateTime.now())
          .set(Order::getCancelReason,
                reason != null && !reason.isBlank() ? reason : CANCEL_REASON_USER);

        boolean claimed = this.update(uw);
        if (!claimed) {
            // 已被并发请求处理，或当前状态不允许取消（3 已完成 / 4 已取消 /
            // 超时回收时用户已付款或商家已发货）
            return;
        }
        // 让内存对象与库内状态保持一致（调用方与单测据此判断结果）
        order.setOrderStatus(4);
        order.setCancelTime(LocalDateTime.now());
        order.setCancelReason(reason != null && !reason.isBlank() ? reason : CANCEL_REASON_USER);

        // 查询订单明细
        List<OrderItem> items = orderItemMapper.selectList(
            new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));

        // 回补库存 —— 【库存扣减时机修复·2026-09-23】只回补「已经扣过」的订单。
        // 扣减现在发生在发货/签收时：未发货即取消的订单从未扣减库存，
        // 若照旧无条件回补，库存会凭空增加（越取消越多）。
        Long deducted = inventoryLogMapper.selectCount(new LambdaQueryWrapper<InventoryLog>()
                .eq(InventoryLog::getOrderId, order.getId())
                .eq(InventoryLog::getOrderType, "order")
                .eq(InventoryLog::getType, 1));
        boolean stockDeducted = deducted != null && deducted > 0;

        if (stockDeducted && items != null && !items.isEmpty()) {
            for (OrderItem item : items) {
                inventoryService.addStock(item.getProductId(), item.getQuantity(),
                    "cancel", order.getId());
                // 【2026-10-10】库存回补的同时把销量对称扣回，保证两者永远同向。
                // 这里不需要额外幂等：能走到本分支说明本单确实扣过库存（stockDeducted），
                // 而条件更新 `IN (0,1,2)` 已保证整个 cancelOrder 只会有一个线程进来。
                // GREATEST(...,0) 在 SQL 层兜底，历史脏数据不会把销量打成负数。
                productMapper.deductSales(item.getProductId(), item.getQuantity());
                log.info("取消订单回补库存: productId={}, quantity={}, orderNo={}",
                    item.getProductId(), item.getQuantity(), order.getOrderNo());
            }
        } else {
            log.info("取消订单无需回补库存（未扣减过）: orderNo={}", order.getOrderNo());
        }

        // 状态已在方法开头的原子占位中写入，此处不再 updateById（避免覆盖并发结果）。
        log.info("订单已取消: orderNo={}, restoredStock={}, reason={}",
            order.getOrderNo(), stockDeducted, order.getCancelReason());
    }

    @Override
    public List<Order> listTimeoutUnpaidOrders(LocalDateTime deadline) {
        if (deadline == null) {
            return Collections.emptyList();
        }
        // 仅取「待付款(0) + 未付款(0 或 NULL) + 创建时间早于截止点」的订单。
        // pay_status 用 ( = 0 OR IS NULL ) 而不是简单的 = 0：本列在库里有 DEFAULT 0，
        // 但历史数据理论上存在未写该列的 NULL 行，而 SQL 的 `= 0` 对 NULL 恒为假 ——
        // 那些单子会被永久漏掉，成为挂在「待付款」里谁都不管的僵尸单。
        // 判定口径与前端 isAwaitingPay（Number(payStatus) !== 1 视为未付款）保持一致。
        //
        // 不加商家维度：超过付款时限的订单，无论属于哪个商家都应当被回收，
        // 否则库存与「待付款」列表会被长期挂着不动的僵尸单占住。
        // @TableLogic 会自动补上 deleted = 0，无需手写。
        return this.list(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderStatus, 0)
                .and(w -> w.eq(Order::getPayStatus, 0).or().isNull(Order::getPayStatus))
                .lt(Order::getCreateTime, deadline)
                .orderByAsc(Order::getCreateTime));
    }

    /**
     * 回填付款截止时间（{@link Order#getPayDeadline()}）。
     *
     * <p>只对待付款(0)、未付款(0) 且 createTime 可用的订单回填；其余保持 null，
     * 前端据此决定是否渲染倒计时，不需要再自己判断状态。
     */
    private void fillPayDeadline(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        for (Order order : orders) {
            if (order == null || order.getCreateTime() == null) {
                continue;
            }
            Integer status = order.getOrderStatus();
            Integer payStatus = order.getPayStatus();
            boolean awaitingPay = status != null && status == 0
                    && (payStatus == null || payStatus == 0);
            if (awaitingPay) {
                order.setPayDeadline(order.getCreateTime().plusMinutes(unpaidTimeoutMinutes));
            }
        }
    }

    private String generateOrderNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long timestamp = System.currentTimeMillis();
        return "ORD" + date + timestamp % 100000;
    }
}