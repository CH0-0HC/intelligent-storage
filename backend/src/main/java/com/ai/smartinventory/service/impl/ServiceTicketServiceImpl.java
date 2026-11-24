package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.AfterSaleRule;
import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.ServiceTicketApplyDTO;
import com.ai.smartinventory.dto.ServiceTicketHandleDTO;
import com.ai.smartinventory.entity.AfterSale;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.ServiceTicket;
import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.mapper.AfterSaleMapper;
import com.ai.smartinventory.mapper.OrderItemMapper;
import com.ai.smartinventory.mapper.OrderMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.mapper.ServiceTicketMapper;
import com.ai.smartinventory.mapper.UserMapper;
import com.ai.smartinventory.service.ServiceTicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 客服裁定工单实现。
 *
 * 【依赖方向】本类只依赖 Mapper（Order / OrderItem / Product / AfterSale / User），
 * **不依赖 AfterSaleService** —— Mapper 之间不存在循环，而 Service 之间会：
 * AfterSaleServiceImpl 必须反向注入本 Service 来查放行授权，这里若再注入
 * AfterSaleService 就会构成环（Spring 虽可 @Lazy 兜住，但那会让启动顺序变得隐晦）。
 * 「有没有进行中的售后单」用 AfterSaleMapper 直接查一次即可，代价是一条 SQL。
 */
@Slf4j
@Service
public class ServiceTicketServiceImpl extends ServiceImpl<ServiceTicketMapper, ServiceTicket>
        implements ServiceTicketService {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private ProductMapper productMapper;

    /** 只读用途：判断该订单是否已有进行中的售后单（避免开重复工单） */
    @Resource
    private AfterSaleMapper afterSaleMapper;

    @Resource
    private UserMapper userMapper;

    // ==================== 买家：可提交性判定 ====================

    @Override
    public Map<String, Object> checkSubmittable(Long userId, Long orderId, Long orderItemId, Long productId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("orderItemId", orderItemId);
        out.put("productId", productId);

        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            out.put("allowed", false);
            out.put("hint", "订单不存在或无权访问");
            return out;
        }

        int status = order.getOrderStatus() == null ? -1 : order.getOrderStatus();
        if (status != AfterSaleRule.ORDER_STATUS_COMPLETED) {
            out.put("allowed", false);
            out.put("hint", "仅「已完成（确认收货）」的订单可以联系客服申请售后");
            return out;
        }
        if (order.getPayStatus() == null || order.getPayStatus() != 1) {
            out.put("allowed", false);
            out.put("hint", "该订单未完成付款，不支持申请售后");
            return out;
        }

        // 已有进行中的售后单：应先等商家处理，不必再开工单。
        // 为什么在这里直接查售后表而不是调 AfterSaleService：见类注释的依赖方向说明。
        // 这一步只是「更友好的前置提示」，即便漏判也不会造成越权 ——
        // 真正的并发闸门在 AfterSaleServiceImpl.apply 里。
        List<AfterSale> history = afterSaleMapper.selectList(
                new LambdaQueryWrapper<AfterSale>().eq(AfterSale::getOrderId, order.getId()));
        boolean processing = history.stream()
                .anyMatch(a -> a.getStatus() != null && AfterSaleRule.isActive(a.getStatus()));
        if (processing) {
            out.put("allowed", false);
            out.put("hint", "售后处理中，请先在「退款/售后」中查看进度");
            return out;
        }

        // 已经有可用放行授权：直接去提交售后，不要再开一张工单
        ServiceTicket usable = findUsableAuthorization(order.getId());
        if (usable != null) {
            out.put("allowed", false);
            out.put("existingTicketId", usable.getId());
            out.put("existingTicketNo", usable.getTicketNo());
            out.put("hint", "客服已同意您的售后申请，请直接提交（授权一次有效，请勿重复联系）");
            return out;
        }

        // 待裁定的工单只能有一张：防同一订单反复开工单
        ServiceTicket pending = this.getOne(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getOrderId, order.getId())
                .eq(ServiceTicket::getUserId, userId)
                .eq(ServiceTicket::getStatus, AfterSaleRule.TICKET_PENDING)
                .orderByDesc(ServiceTicket::getId)
                .last("LIMIT 1"), false);
        if (pending != null) {
            out.put("allowed", false);
            out.put("existingTicketId", pending.getId());
            out.put("existingTicketNo", pending.getTicketNo());
            out.put("hint", "您已提交过客服工单（" + pending.getTicketNo() + "），请等待客服裁定");
            return out;
        }

        // 商品级退货期限（按商品咨询时用该商品自己的期限；整单咨询取最严一档）
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        out.put("items", items.stream().map(this::toItemBrief).collect(Collectors.toList()));
        out.put("orderNo", order.getOrderNo());
        out.put("completeTime", order.getCompleteTime());

        int windowDays;
        if (productId != null) {
            Product product = productMapper.selectById(productId);
            if (product == null) {
                out.put("allowed", false);
                out.put("hint", "商品不存在或已下架，无法申诉");
                return out;
            }
            windowDays = AfterSaleRule.normalizeReturnWindowDays(product.getReturnWindowDays());
            out.put("productName", product.getName());
        } else {
            // 整单咨询：直接复用规则中心的「最严一档」计算
            Map<Long, Product> productMap = loadProducts(items);
            windowDays = AfterSaleRule.normalizeReturnWindowDays(
                    strictestFromOrder(items, productMap));
        }
        out.put("returnWindowDays", windowDays);
        out.put("returnWindowText", AfterSaleRule.returnWindowText(windowDays));

        LocalDateTime start = order.getCompleteTime() != null ? order.getCompleteTime() : order.getUpdateTime();
        out.put("completeTime", start);
        int overdue = 0;
        if (start != null) {
            long passed = java.time.Duration.between(start, LocalDateTime.now()).toDays();
            overdue = windowDays == AfterSaleRule.RETURN_WINDOW_NONE
                    ? (int) Math.max(0, passed)
                    : (int) Math.max(0, passed - windowDays);
        }
        out.put("daysOverdue", overdue);

        // 只有「已经超出退货期限」才需要客服介入；期限内请直接走正常售后入口
        // 【M-4 修复】统一走 AfterSaleRule 的唯一超期口径（闭区间），
        // 否则期限第 N 天当天会与售后申请侧给出相反结论。
        boolean over = AfterSaleRule.isPastReturnDeadline(start, windowDays, LocalDateTime.now());
        if (!over) {
            out.put("allowed", false);
            out.put("hint", "该商品仍在退货期限内（" + AfterSaleRule.returnWindowText(windowDays)
                    + "），请直接在「我的订单」中申请售后");
            return out;
        }

        out.put("allowed", true);
        out.put("hint", windowDays == AfterSaleRule.RETURN_WINDOW_NONE
                ? "该商品不支持退货退款；如确有质量问题，请提交说明与凭证，由客服依据商家标准裁定"
                : "已超出该商品退货期限 " + overdue + " 天，请提交说明与凭证，客服将依据商家规则裁定");
        return out;
    }

    private Map<String, Object> toItemBrief(OrderItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderItemId", item.getId());
        m.put("productId", item.getProductId());
        m.put("productName", item.getProductName());
        m.put("quantity", item.getQuantity());
        m.put("price", item.getPrice());
        m.put("productImage", item.getProductImage());
        return m;
    }

    // ==================== 买家：提交 / 查询 / 撤销 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket submit(Long userId, ServiceTicketApplyDTO dto) {
        if (dto == null || dto.getOrderId() == null) {
            throw new BusinessException(400, "缺少订单信息");
        }

        // 归属 + 可提交性：整条判定链复用 checkSubmittable，
        // 避免「页面说能提、提交却被拒」的两套口径。
        Long productId = dto.getProductId();
        if (productId == null && dto.getOrderItemId() != null) {
            OrderItem item = orderItemMapper.selectById(dto.getOrderItemId());
            if (item != null && dto.getOrderId().equals(item.getOrderId())) {
                productId = item.getProductId();
            }
        }
        Map<String, Object> check = checkSubmittable(userId, dto.getOrderId(), dto.getOrderItemId(), productId);
        if (!Boolean.TRUE.equals(check.get("allowed"))) {
            throw new BusinessException(400, String.valueOf(check.get("hint")));
        }
        if (!AfterSaleRule.isValidReason(dto.getReasonCode())) {
            throw new BusinessException(400, "请选择有效的诉求原因");
        }
        if (dto.getDescription() != null && dto.getDescription().length() > 500) {
            throw new BusinessException(400, "补充说明不能超过 500 字");
        }
        if (dto.getImages() != null && dto.getImages().size() > 6) {
            throw new BusinessException(400, "最多上传 6 张凭证图片");
        }

        Order order = orderMapper.selectById(dto.getOrderId());
        ServiceTicket entity = new ServiceTicket();
        entity.setTicketNo(generateTicketNo());
        entity.setOrderId(order.getId());
        entity.setOrderNo(order.getOrderNo());
        entity.setOrderItemId(dto.getOrderItemId());
        entity.setProductId(productId);
        // 商家归属快照：商品被改归属 / 删除后，裁定口径仍按申请当时的商家执行
        entity.setMerchantId(productId == null ? null : merchantIdOf(productId));
        entity.setUserId(userId);
        entity.setReasonCode(dto.getReasonCode());
        entity.setDescription(dto.getDescription());
        entity.setImages(dto.getImages() == null ? Collections.emptyList() : dto.getImages());
        entity.setReturnWindowDays((Integer) check.get("returnWindowDays"));
        entity.setDaysOverdue((Integer) check.get("daysOverdue"));
        entity.setStatus(AfterSaleRule.TICKET_PENDING);
        entity.setConsumed(0);

        if (!this.save(entity)) {
            throw new BusinessException(500, "客服工单提交失败，请稍后重试");
        }
        log.info("客服工单提交成功: ticketNo={}, orderNo={}, userId={}, reason={}, overdueDays={}",
                entity.getTicketNo(), order.getOrderNo(), userId, dto.getReasonCode(), entity.getDaysOverdue());
        return decorate(entity);
    }

    @Override
    public PageResult<ServiceTicket> getUserPage(Long userId, Integer page, Integer size, Integer status) {
        int p = PageBounds.page(page);
        int s = PageBounds.size(size);

        LambdaQueryWrapper<ServiceTicket> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ServiceTicket::getUserId, userId);
        if (status != null && status >= 0) {
            wrapper.eq(ServiceTicket::getStatus, status);
        }
        wrapper.orderByDesc(ServiceTicket::getId);

        Page<ServiceTicket> result = this.page(new Page<>(p, s), wrapper);
        decorateAll(result.getRecords());
        return new PageResult<>(result.getTotal(), result.getRecords(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket cancelByBuyer(Long id, Long userId) {
        ServiceTicket ticket = this.getById(id);
        if (ticket == null || !userId.equals(ticket.getUserId())) {
            throw new BusinessException(403, "无权操作该客服工单");
        }
        boolean updated = this.update(new LambdaUpdateWrapper<ServiceTicket>()
                .eq(ServiceTicket::getId, id)
                .eq(ServiceTicket::getStatus, AfterSaleRule.TICKET_PENDING)
                .set(ServiceTicket::getStatus, AfterSaleRule.TICKET_CANCELLED));
        if (!updated) {
            throw new BusinessException(400, "仅「待客服裁定」的工单可以撤销，或工单状态已变化");
        }
        log.info("买家撤销客服工单: ticketNo={}, userId={}", ticket.getTicketNo(), userId);
        return this.getById(id);
    }

    // ==================== 客服 / 管理员 ====================

    @Override
    public PageResult<ServiceTicket> getManagePage(Integer page, Integer size, Integer status, String keyword) {
        int p = PageBounds.page(page);
        int s = PageBounds.size(size);

        LambdaQueryWrapper<ServiceTicket> wrapper = new LambdaQueryWrapper<>();
        if (status != null && status >= 0) {
            wrapper.eq(ServiceTicket::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            // 订单号 / 工单号模糊匹配（客服最常见的两个检索入口）
            wrapper.and(w -> w.like(ServiceTicket::getOrderNo, kw).or().like(ServiceTicket::getTicketNo, kw));
        }
        wrapper.orderByDesc(ServiceTicket::getId);

        Page<ServiceTicket> result = this.page(new Page<>(p, s), wrapper);
        decorateAll(result.getRecords());
        return new PageResult<>(result.getTotal(), result.getRecords(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket resolve(Long id, Long operatorId, ServiceTicketHandleDTO dto) {
        ServiceTicket ticket = this.getById(id);
        if (ticket == null) {
            throw new BusinessException(400, "客服工单不存在");
        }
        Integer resolution = dto == null ? null : dto.getResolution();
        if (!AfterSaleRule.RESOLUTION_ALLOW.equals(resolution)
                && !AfterSaleRule.RESOLUTION_REJECT.equals(resolution)) {
            throw new BusinessException(400, "请选择裁定结果（同意继续申请 / 驳回）");
        }
        String remark = dto.getRemark() == null ? null : dto.getRemark().trim();
        if (AfterSaleRule.RESOLUTION_REJECT.equals(resolution) && (remark == null || remark.isEmpty())) {
            // 驳回必须写明依据：用户有权知道「按哪条商家规则被拒」
            throw new BusinessException(400, "驳回时必须填写裁定说明（依据的商家退货规则）");
        }
        if (remark != null && remark.length() > 500) {
            throw new BusinessException(400, "裁定说明不能超过 500 字");
        }

        boolean updated = this.update(new LambdaUpdateWrapper<ServiceTicket>()
                .eq(ServiceTicket::getId, id)
                .eq(ServiceTicket::getStatus, AfterSaleRule.TICKET_PENDING)
                .set(ServiceTicket::getStatus, AfterSaleRule.TICKET_RESOLVED)
                .set(ServiceTicket::getResolution, resolution)
                .set(ServiceTicket::getCsRemark, remark)
                .set(ServiceTicket::getOperatorId, operatorId)
                .set(ServiceTicket::getOperateTime, LocalDateTime.now()));
        if (!updated) {
            throw new BusinessException(400, "该工单已裁定或已取消，请刷新后查看");
        }
        log.info("客服工单裁定完成: ticketNo={}, resolution={}, operatorId={}",
                ticket.getTicketNo(), resolution, operatorId);
        return decorate(this.getById(id));
    }

    // ==================== 放行授权 ====================

    @Override
    public ServiceTicket findUsableAuthorization(Long orderId) {
        if (orderId == null) {
            return null;
        }
        return this.getOne(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getOrderId, orderId)
                .eq(ServiceTicket::getStatus, AfterSaleRule.TICKET_RESOLVED)
                .eq(ServiceTicket::getResolution, AfterSaleRule.RESOLUTION_ALLOW)
                .eq(ServiceTicket::getConsumed, 0)
                .orderByDesc(ServiceTicket::getId)
                .last("LIMIT 1"), false);
    }

    @Override
    public Map<Long, ServiceTicket> findUsableAuthorizations(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ServiceTicket> tickets = this.list(new LambdaQueryWrapper<ServiceTicket>()
                .in(ServiceTicket::getOrderId, orderIds)
                .eq(ServiceTicket::getStatus, AfterSaleRule.TICKET_RESOLVED)
                .eq(ServiceTicket::getResolution, AfterSaleRule.RESOLUTION_ALLOW)
                .eq(ServiceTicket::getConsumed, 0));
        // 同一订单可能有多张历史放行工单（例如第一张过期作废后又裁定一张），
        // 取 id 最大的（最新）那张作为当前有效授权。
        Map<Long, ServiceTicket> byOrder = new HashMap<>();
        for (ServiceTicket t : tickets) {
            ServiceTicket prev = byOrder.get(t.getOrderId());
            if (prev == null || (t.getId() != null && prev.getId() != null && t.getId() > prev.getId())) {
                byOrder.put(t.getOrderId(), t);
            }
        }
        return byOrder;
    }

    @Override
    public boolean consumeAuthorization(Long ticketId) {
        if (ticketId == null) {
            return false;
        }
        // 条件更新（consumed=0 → 1）：并发下只有一次能成功，
        // 从而保证「一张工单只放行一次售后」在并发场景也成立。
        return this.update(new LambdaUpdateWrapper<ServiceTicket>()
                .eq(ServiceTicket::getId, ticketId)
                .eq(ServiceTicket::getConsumed, 0)
                .set(ServiceTicket::getConsumed, 1));
    }

    // ==================== 详情 / 工具 ====================

    @Override
    public ServiceTicket getByIdWithPermission(Long id, Long userId, String role) {
        ServiceTicket ticket = this.getById(id);
        if (ticket == null) {
            return null;
        }
        if (MerchantContext.ROLE_ADMIN.equals(role) || MerchantContext.ROLE_KEFU.equals(role)) {
            return decorate(ticket);
        }
        if (ticket.getUserId() != null && ticket.getUserId().equals(userId)) {
            return decorate(ticket);
        }
        return null;
    }

    /** 批量补齐展示字段（商品名 / 买家名 / 客服名 / 订单实付），避免 N+1。 */
    private void decorateAll(List<ServiceTicket> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> productIds = records.stream()
                .map(ServiceTicket::getProductId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> productNames = Collections.emptyMap();
        if (!productIds.isEmpty()) {
            productNames = productMapper.selectBatchIds(productIds).stream()
                    .filter(p -> p != null && p.getId() != null)
                    .collect(Collectors.toMap(Product::getId, Product::getName, (a, b) -> a));
        }

        Set<Long> userIds = records.stream()
                .map(ServiceTicket::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> operatorIds = records.stream()
                .map(ServiceTicket::getOperatorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> allUserIds = new java.util.HashSet<>(userIds);
        allUserIds.addAll(operatorIds);
        Map<Long, String> userNames = Collections.emptyMap();
        if (!allUserIds.isEmpty()) {
            userNames = userMapper.selectBatchIds(allUserIds).stream()
                    .filter(u -> u != null && u.getId() != null)
                    .collect(Collectors.toMap(User::getId,
                            u -> u.getNickname() != null ? u.getNickname() : u.getUsername(),
                            (a, b) -> a));
        }

        Set<Long> orderIds = records.stream()
                .map(ServiceTicket::getOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Order> orders = Collections.emptyMap();
        if (!orderIds.isEmpty()) {
            orders = orderMapper.selectBatchIds(orderIds).stream()
                    .filter(o -> o != null && o.getId() != null)
                    .collect(Collectors.toMap(Order::getId, o -> o, (a, b) -> a));
        }

        for (ServiceTicket t : records) {
            if (t.getProductId() != null) {
                t.setProductName(productNames.get(t.getProductId()));
            }
            t.setBuyerName(userNames.get(t.getUserId()));
            t.setOperatorName(userNames.get(t.getOperatorId()));
            Order o = orders.get(t.getOrderId());
            if (o != null) {
                t.setOrderPayAmount(o.getPayAmount());
            }
        }
    }

    private ServiceTicket decorate(ServiceTicket ticket) {
        if (ticket == null) {
            return null;
        }
        decorateAll(Collections.singletonList(ticket));
        return ticket;
    }

    private Long merchantIdOf(Long productId) {
        if (productId == null) {
            return null;
        }
        Product product = productMapper.selectById(productId);
        return product == null ? null : product.getMerchantId();
    }

    private Map<Long, Product> loadProducts(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = items.stream()
                .map(OrderItem::getProductId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Product> map = new HashMap<>();
        for (Product p : productMapper.selectBatchIds(ids)) {
            if (p != null && p.getId() != null) {
                map.put(p.getId(), p);
            }
        }
        return map;
    }

    /**
     * 取订单明细对应的「最严一档」退货期限。
     *
     * <p>语义与 {@link AfterSaleRule} 内部实现一致；此处独立实现是因为
     * 那边的方法是私有的，而把私有方法暴露成 public 会扩大规则中心的面。
     * 两处都调用 {@link AfterSaleRule#normalizeReturnWindowDays(Integer)}，
     * 归一化口径仍然只有一份。
     *
     * <p>注意：入参是**订单明细列表**而不是 Order —— 这里查出来的 items 并未
     * 回填到 order.items 上，若传 Order 会因 items 为空而静默退化成「默认 15 天」。
     *
     * @param items      订单明细
     * @param productMap productId → Product
     * @return 最严一档的期限（天）
     */
    private Integer strictestFromOrder(List<OrderItem> items, Map<Long, Product> productMap) {
        if (items == null || items.isEmpty()) {
            return AfterSaleRule.DEFAULT_RETURN_WINDOW_DAYS;
        }
        int strictest = Integer.MAX_VALUE;
        boolean found = false;
        for (OrderItem item : items) {
            Product p = item == null || item.getProductId() == null ? null : productMap.get(item.getProductId());
            if (p == null) {
                continue;
            }
            found = true;
            strictest = Math.min(strictest, AfterSaleRule.normalizeReturnWindowDays(p.getReturnWindowDays()));
        }
        return found ? strictest : AfterSaleRule.DEFAULT_RETURN_WINDOW_DAYS;
    }

    private String generateTicketNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "CS" + date + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
