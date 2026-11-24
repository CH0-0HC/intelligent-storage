package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.AfterSaleRule;
import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.AfterSaleApplyDTO;
import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.dto.AfterSaleHandleDTO;
import com.ai.smartinventory.dto.AfterSaleItemApplyDTO;
import com.ai.smartinventory.entity.AfterSale;
import com.ai.smartinventory.entity.AfterSaleItem;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.ServiceTicket;
import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.mapper.AfterSaleItemMapper;
import com.ai.smartinventory.mapper.AfterSaleMapper;
import com.ai.smartinventory.mapper.InventoryLogMapper;
import com.ai.smartinventory.mapper.OrderItemMapper;
import com.ai.smartinventory.mapper.OrderMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.mapper.UserMapper;
import com.ai.smartinventory.service.AfterSaleService;
import com.ai.smartinventory.service.InventoryService;
import com.ai.smartinventory.service.ServiceTicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 售后申请服务实现。
 *
 * 【依赖方向约定】本类只依赖 Mapper（OrderMapper / OrderItemMapper）与
 * InventoryService，**不依赖 OrderService**。
 * 原因是 OrderServiceImpl 需要反向注入 AfterSaleService 以在订单列表回填
 * 售后状态，若这里再注入 OrderService 就会构成循环依赖
 * （Spring 虽可 @Lazy 兜住，但那会让启动顺序变得隐晦）。
 */
@Slf4j
@Service
public class AfterSaleServiceImpl extends ServiceImpl<AfterSaleMapper, AfterSale> implements AfterSaleService {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private InventoryService inventoryService;

    @Resource
    private InventoryLogMapper inventoryLogMapper;

    /** 【P1-6】按商品售后：after_sale_item 的读写入口 */
    @Resource
    private AfterSaleItemMapper afterSaleItemMapper;

    /** 【P1-6】申请时把 product.merchant_id 快照进 after_sale_item */
    @Resource
    private ProductMapper productMapper;

    /**
     * 【2026-09-28】客服放行授权。
     *
     * <p>依赖方向是单向的：本 Service → ServiceTicketService。
     * 反向（ServiceTicketServiceImpl → AfterSaleService）刻意**没有**建立，
     * 那边只注入 AfterSaleMapper 读一张表，因此不构成循环依赖。
     */
    @Resource
    private ServiceTicketService serviceTicketService;

    /** 退货退款完成时回补库存的流水类型标记 */
    private static final String STOCK_LOG_TYPE_AFTER_SALE = "after_sale";

    // ==================== 资格判定 ====================

    @Override
    public AfterSaleEligibility evaluateForBuyer(Order order, Long userId) {
        if (order == null) {
            return AfterSaleRule.evaluate(null, null, LocalDateTime.now());
        }
        if (userId != null && !userId.equals(order.getUserId())) {
            // 与项目既有约定一致：不属于自己的订单一律 403，
            // 不区分「不存在」与「属于别人」，避免被枚举探测。
            throw new BusinessException(403, "无权访问该订单");
        }
        return evaluateWithRules(order, listByOrder(order.getId()), LocalDateTime.now());
    }

    /**
     * 带「商品级退货期限 + 客服放行」的完整判定。
     *
     * <p>取数职责留在本层（规则中心保持纯函数），三样输入：
     * <ol>
     *   <li>订单明细 —— 已由 OrderService 回填；未回填时补一次，保证期限算得出来；</li>
     *   <li>商品 —— 一次批量查询拿 returnWindowDays / returnNoReason；</li>
     *   <li>可用放行工单 —— 超期后客服已同意的凭证。</li>
     * </ol>
     */
    private AfterSaleEligibility evaluateWithRules(Order order, List<AfterSale> history, LocalDateTime now) {
        ensureItems(order);
        Map<Long, Product> productsById = loadProductsOf(Collections.singletonList(order));
        Map<Long, ServiceTicket> approved = serviceTicketService
                .findUsableAuthorizations(Collections.singletonList(order.getId()));
        return AfterSaleRule.evaluate(order, history, now, productsById, approved);
    }

    /** 订单明细缺失时补一次（规则中心要靠 items 取商品 ID 算期限）。 */
    private void ensureItems(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }
        if (order.getItems() != null && !order.getItems().isEmpty()) {
            return;
        }
        order.setItems(orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId())));
    }

    /** 批量取「订单 → 商品」映射（一次 SQL，避免逐单查库）。 */
    private Map<Long, Product> loadProductsOf(List<Order> orders) {
        Set<Long> productIds = new HashSet<>();
        for (Order o : orders) {
            if (o == null || o.getItems() == null) {
                continue;
            }
            for (OrderItem item : o.getItems()) {
                if (item != null && item.getProductId() != null) {
                    productIds.add(item.getProductId());
                }
            }
        }
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Product> map = new HashMap<>();
        for (Product p : productMapper.selectBatchIds(productIds)) {
            if (p != null && p.getId() != null) {
                map.put(p.getId(), p);
            }
        }
        return map;
    }

    @Override
    public Map<Long, AfterSaleEligibility> evaluateBatch(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> orderIds = orders.stream()
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 一次取回这批订单的全部售后单再内存分组，避免逐单查询造成 N+1
        List<AfterSale> all = listByOrderIds(orderIds);
        Map<Long, List<AfterSale>> grouped = all.stream()
                .collect(Collectors.groupingBy(AfterSale::getOrderId));

        // 【2026-09-28】商品级退货期限 + 客服放行：各一次批量查询，仍是 O(1) 次 SQL
        Map<Long, Product> productsById = loadProductsOf(orders);
        Map<Long, ServiceTicket> approved = serviceTicketService.findUsableAuthorizations(orderIds);

        LocalDateTime now = LocalDateTime.now();
        return orders.stream()
                .filter(o -> o != null && o.getId() != null)
                .collect(Collectors.toMap(
                        Order::getId,
                        o -> AfterSaleRule.evaluate(o, grouped.get(o.getId()), now, productsById, approved),
                        (a, b) -> a));
    }

    // ==================== 买家：申请 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AfterSale apply(Long userId, AfterSaleApplyDTO dto) {
        if (dto == null || dto.getOrderId() == null) {
            throw new BusinessException(400, "缺少订单信息");
        }

        Order order = orderMapper.selectById(dto.getOrderId());
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(403, "无权对该订单申请售后");
        }
        // 规则判定要靠订单明细取商品退货期限，先补齐
        ensureItems(order);

        // 1) 状态 + 时效 + 并发限制，统一走规则中心（含商品级退货期限与客服放行）
        List<AfterSale> history = listByOrder(order.getId());
        AfterSaleEligibility eligibility = evaluateWithRules(order, history, LocalDateTime.now());
        if (!Boolean.TRUE.equals(eligibility.getEligible())) {
            throw new BusinessException(400, eligibility.getHint());
        }

        // 1.1) 【2026-09-28】超期放行：必须持有客服裁定通过的工单，
        //      且该工单属于本订单、尚未被消费。这是「由客服依据商家指定标准
        //      裁定后，用户方可继续申请」在**提交侧**的强制落点 ——
        //      前端按钮可不可点只是体验，这里才是不可绕过的闸门。
        Long csTicketId = null;
        boolean csAuthorized = AfterSaleRule.CODE_CS_APPROVED.equals(eligibility.getCode());
        if (csAuthorized) {
            ServiceTicket ticket = serviceTicketService.findUsableAuthorization(order.getId());
            if (!AfterSaleRule.isUsableAuthorization(ticket)) {
                throw new BusinessException(400, "客服放行授权已失效，请重新联系客服");
            }
            LocalDateTime csDeadline = AfterSaleRule.authorizationDeadline(ticket);
            if (csDeadline != null && LocalDateTime.now().isAfter(csDeadline)) {
                throw new BusinessException(400,
                        "客服放行授权已过期（有效期 " + AfterSaleRule.CS_AUTHORIZATION_DAYS + " 天），请重新联系客服");
            }
            csTicketId = ticket.getId();
        }

        // 2) 类型 / 原因合法性
        if (!AfterSaleRule.isValidType(dto.getType())) {
            throw new BusinessException(400, "请选择有效的售后类型");
        }
        if (!AfterSaleRule.isValidReason(dto.getReasonCode())) {
            throw new BusinessException(400, "请选择有效的售后原因");
        }
        if (AfterSaleRule.REASON_NO_REASON.equals(dto.getReasonCode())
                && !AfterSaleRule.allowsNoReason(dto.getType())) {
            throw new BusinessException(400, "换货请选择具体原因，「无理由」仅适用于退款类售后");
        }
        // 3) 「无理由」的独立时效与商品政策。
        //    两个维度分开表达，避免把「商家不接无理由」误报成「超期」：
        //      a) 商品政策 —— 商家在商品上关掉了无理由 ⇒ 任何情况下都不接（客服放行也不行，
        //         因为放行的是「超期」，不是「改写商家的退货品类政策」）；
        //      b) 时间窗口 —— 超出无理由期 ⇒ 不接；但**客服已放行的超期申请**必须除外，
        //         否则本次改造打开的通道会被这条旧校验重新关死。
        if (AfterSaleRule.REASON_NO_REASON.equals(dto.getReasonCode())) {
            if (Boolean.FALSE.equals(eligibility.getReturnNoReasonAllowed())) {
                throw new BusinessException(400,
                        "该商品不支持无理由退货，请选择质量问题等原因申请");
            }
            if (!csAuthorized
                    && (eligibility.getNoReasonDaysLeft() == null || eligibility.getNoReasonDaysLeft() <= 0)) {
                throw new BusinessException(400,
                        "已超过无理由退货期（" + AfterSaleRule.NO_REASON_DAYS + " 天），不支持「无理由」退货；"
                                + "如商品存在质量问题，请选择对应原因申请，或先联系客服");
            }
        }

        // 3.1) 按商品申请（P1-6）：解析 dto.items。
        //   为空 / 缺省 ⇒ 整单售后（itemScope=0），金额与后续回补行为与改造前逐字一致；
        //   非空 ⇒ 校验归属与数量、计算明细金额，落 itemScope=1 并写 after_sale_item。
        List<AfterSaleItemApplyDTO> claimedItems = dto.getItems() == null
                ? Collections.emptyList()
                : dto.getItems();
        boolean byItem = !claimedItems.isEmpty();

        Map<Long, OrderItem> orderItemMap = Collections.emptyMap();
        List<AfterSaleItem> saleItemRows = Collections.emptyList();
        BigDecimal itemAmount = BigDecimal.ZERO;
        if (byItem) {
            List<Long> orderItemIds = claimedItems.stream()
                    .map(c -> c == null ? null : c.getOrderItemId())
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            // 去重后数量变少 ⇒ 有明细缺失 orderItemId、或同一 order_item 被重复提交
            //（后者会撞 uk_sale_item，不如在这里就给明确的 400）
            if (orderItemIds.size() != claimedItems.size()) {
                throw new BusinessException(400, "售后商品明细有误（存在缺失或重复），请重新选择");
            }
            for (AfterSaleItemApplyDTO claim : claimedItems) {
                if (claim.getRemark() != null && claim.getRemark().length() > 255) {
                    throw new BusinessException(400, "商品备注不能超过 255 字");
                }
            }
            // orderItemId 必须属于本订单：带上 order_id 条件查询，避免指定他人订单的明细
            List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                    .eq(OrderItem::getOrderId, order.getId())
                    .in(OrderItem::getId, orderItemIds));
            orderItemMap = orderItems.stream()
                    .collect(Collectors.toMap(OrderItem::getId, i -> i, (a, b) -> a));
            if (orderItemMap.size() != orderItemIds.size()) {
                throw new BusinessException(400, "所选商品不属于该订单，请刷新后重试");
            }
            for (AfterSaleItemApplyDTO claim : claimedItems) {
                OrderItem orderItem = orderItemMap.get(claim.getOrderItemId());
                int quantity = claim.getQuantity() == null ? 0 : claim.getQuantity();
                int ordered = orderItem.getQuantity() == null ? 0 : orderItem.getQuantity();
                if (!AfterSaleRule.isValidItemQuantity(quantity, ordered)) {
                    throw new BusinessException(400, "「" + orderItem.getProductName()
                            + "」的退货数量需大于 0 且不超过购买数量 " + ordered);
                }
                // 不可超退：该商品（订单 + 商品）已生效的售后数量也要计入
                int claimed = afterSaleItemMapper.sumClaimedQuantity(order.getId(), orderItem.getProductId());
                if (!AfterSaleRule.isValidItemQuantity(quantity + claimed, ordered)) {
                    throw new BusinessException(400, "「" + orderItem.getProductName()
                            + "」可退数量不足，此前已申请 " + claimed + " 件");
                }
            }
            itemAmount = AfterSaleRule.maxRefundableForItems(claimedItems, orderItemMap);
            saleItemRows = buildSaleItems(order, claimedItems, orderItemMap);
        }

        // 4) 金额：换货不涉及退款，强制 0；其余默认按订单实付金额
        BigDecimal refundAmount;
        if (AfterSaleRule.TYPE_EXCHANGE == dto.getType()) {
            refundAmount = BigDecimal.ZERO;
        } else if (byItem) {
            if (dto.getRefundAmount() == null) {
                // 按商品：缺省取「明细金额合计」与订单实付金额的较小值
                refundAmount = order.getPayAmount() == null
                        ? itemAmount
                        : itemAmount.min(order.getPayAmount());
            } else {
                // 显式传入：先沿用旧口径，再追加「不超过所选商品可退金额」的收紧
                refundAmount = dto.getRefundAmount();
                if (!AfterSaleRule.isValidRefundAmount(refundAmount, order.getPayAmount())) {
                    throw new BusinessException(400, "退款金额需大于 0 且不超过订单实付金额 ¥" + order.getPayAmount());
                }
                if (refundAmount.compareTo(itemAmount.add(new BigDecimal("0.01"))) > 0) {
                    throw new BusinessException(400, "退款金额不能超过所选商品的可退金额 ¥" + itemAmount);
                }
            }
        } else {
            refundAmount = dto.getRefundAmount() != null ? dto.getRefundAmount() : order.getPayAmount();
            if (!AfterSaleRule.isValidRefundAmount(refundAmount, order.getPayAmount())) {
                throw new BusinessException(400, "退款金额需大于 0 且不超过订单实付金额 ¥" + order.getPayAmount());
            }
        }

        if (dto.getDescription() != null && dto.getDescription().length() > 500) {
            throw new BusinessException(400, "补充说明不能超过 500 字");
        }
        if (dto.getImages() != null && dto.getImages().size() > 6) {
            throw new BusinessException(400, "最多上传 6 张凭证图片");
        }

        AfterSale entity = new AfterSale();
        entity.setAfterSaleNo(generateAfterSaleNo());
        entity.setOrderId(order.getId());
        entity.setOrderNo(order.getOrderNo());
        entity.setUserId(userId);
        entity.setType(dto.getType());
        entity.setReasonCode(dto.getReasonCode());
        entity.setReason(AfterSaleRule.reasonText(dto.getReasonCode()));
        entity.setDescription(dto.getDescription());
        entity.setImages(dto.getImages() == null ? Collections.emptyList() : dto.getImages());
        entity.setRefundAmount(refundAmount);
        entity.setItemScope(byItem ? AfterSaleRule.SCOPE_ITEM : AfterSaleRule.SCOPE_ORDER);
        // 【2026-09-28】超期放行的可追溯依据：哪张客服工单同意了他这次申请
        entity.setCsTicketId(csTicketId);
        entity.setStatus(AfterSaleRule.STATUS_PENDING);
        entity.setApplyTime(LocalDateTime.now());

        if (!this.save(entity)) {
            throw new BusinessException(500, "售后申请提交失败，请稍后重试");
        }

        // 【2026-09-28】消费放行授权：条件更新（consumed 0→1），并发下只有一次能成功。
        // 放在 save 之后、同一事务内：若此处失败则整单回滚，不会出现
        // 「售后单已建但工单还能再用一次」的中间态（反之亦然）。
        if (csTicketId != null) {
            if (!serviceTicketService.consumeAuthorization(csTicketId)) {
                throw new BusinessException(400, "客服放行授权已被使用，请重新联系客服");
            }
            log.info("已消费客服放行授权: csTicketId={}, afterSaleNo={}", csTicketId, entity.getAfterSaleNo());
        }

        // 按商品售后：同一事务内落明细。撞 uk_sale_item(after_sale_id, order_item_id)
        // 会连同上面的售后单一并回滚，不会留下「有单无明细」的中间态。
        if (byItem) {
            for (AfterSaleItem row : saleItemRows) {
                row.setAfterSaleId(entity.getId());
                afterSaleItemMapper.insert(row);
            }
        }

        log.info("售后申请提交成功: afterSaleNo={}, orderNo={}, userId={}, type={}, reason={}, amount={}",
                entity.getAfterSaleNo(), order.getOrderNo(), userId, entity.getType(),
                entity.getReasonCode(), refundAmount);
        return entity;
    }

    /**
     * 组装待落库的售后明细行（merchant_id 从 product 快照）。
     * 只组装、不落库；after_sale_id 由调用方在售后单保存后回填。
     *
     * 【为什么快照 merchant_id】归属校验若实时 join product，商品被物理删除后
     * INNER JOIN 会静默丢行，导致「校验通过却没有明细」（见 AfterSaleItem 类注释）。
     */
    private List<AfterSaleItem> buildSaleItems(Order order,
                                               List<AfterSaleItemApplyDTO> claimedItems,
                                               Map<Long, OrderItem> orderItemMap) {
        List<Long> productIds = orderItemMap.values().stream()
                .map(OrderItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Long> merchantByProduct = new HashMap<>();
        if (!productIds.isEmpty()) {
            // 注意：不能直接 Collectors.toMap —— product.merchant_id 可能为 null，toMap 会抛 NPE
            for (Product product : productMapper.selectBatchIds(productIds)) {
                if (product != null && product.getId() != null && product.getMerchantId() != null) {
                    merchantByProduct.put(product.getId(), product.getMerchantId());
                }
            }
        }

        List<AfterSaleItem> rows = new ArrayList<>(claimedItems.size());
        for (AfterSaleItemApplyDTO claim : claimedItems) {
            OrderItem orderItem = orderItemMap.get(claim.getOrderItemId());
            int quantity = claim.getQuantity() == null ? 0 : claim.getQuantity();
            AfterSaleItem row = new AfterSaleItem();
            row.setOrderId(order.getId());
            row.setOrderItemId(orderItem.getId());
            row.setProductId(orderItem.getProductId());
            row.setMerchantId(merchantByProduct.get(orderItem.getProductId()));
            row.setQuantity(quantity);
            row.setAmount(orderItem.getPrice() == null
                    ? BigDecimal.ZERO
                    : orderItem.getPrice().multiply(BigDecimal.valueOf(quantity)));
            row.setRemark(claim.getRemark());
            rows.add(row);
        }
        return rows;
    }

    // ==================== 买家：查询 / 撤销 ====================

    @Override
    public PageResult<AfterSale> getUserPage(Long userId, Integer page, Integer size, Integer status) {
        // 【P1-3】统一走 PageBounds 夹取（页码>=1，size 落 1..500）
        int p = PageBounds.page(page);
        int s = PageBounds.size(size);

        LambdaQueryWrapper<AfterSale> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AfterSale::getUserId, userId);
        if (status != null && status >= 0) {
            wrapper.eq(AfterSale::getStatus, status);
        }
        wrapper.orderByDesc(AfterSale::getId);

        Page<AfterSale> result = this.page(new Page<>(p, s), wrapper);
        return new PageResult<>(result.getTotal(), result.getRecords(), result.getCurrent(), result.getSize());
    }

    @Override
    public AfterSale getByIdWithPermission(Long id, Long userId, String role) {
        AfterSale afterSale = this.getById(id);
        if (afterSale == null) {
            return null;
        }
        // 【2026-09-28】客服需要看售后单详情才能判断超期诉求是否成立
        // （工单详情只带申诉信息，真正的退款金额/明细在这里），
        // 因此与 admin 同等放行。客服仍不能处理售后单 —— 见 handle() 的注释。
        if (MerchantContext.ROLE_ADMIN.equals(role) || MerchantContext.ROLE_KEFU.equals(role)) {
            // 【P1-6】详情接口同样回填 saleItems（item_scope=1 时前端据此渲染「售后商品明细」区块）
            fillSaleItems(Collections.singletonList(afterSale));
            return afterSale;
        }
        if (MerchantContext.ROLE_MERCHANT.equals(role)) {
            if (!isAfterSaleOwnedByCurrentMerchant(afterSale)) {
                return null;
            }
            // 【P1-6】item_scope=1 追加明细级判定：本商家在该售后单下无任何明细 ⇒
            // 视为无权（返回 null，由控制器转 403）。item_scope=0 保持旧判定不变
            // （兼容红线：verify-after-sale.mjs:386-389 走的正是 item_scope=0）。
            // 命中时顺带回填 myItems，供详情页展示「本商家明细」。
            if (Integer.valueOf(AfterSaleRule.SCOPE_ITEM).equals(afterSale.getItemScope())) {
                Long merchantId = MerchantContext.currentMerchantId();
                List<AfterSaleItem> mine = merchantId == null ? Collections.emptyList()
                        : afterSaleItemMapper.selectByAfterSaleAndMerchant(afterSale.getId(), merchantId);
                if (mine == null || mine.isEmpty()) {
                    return null;
                }
                afterSale.setMyItems(mine);
            }
            // 【P1-6】详情接口同样回填 saleItems；商家上下文下 fillSaleItems 会一并回填 myItems。
            fillSaleItems(Collections.singletonList(afterSale));
            return afterSale;
        }
        if (afterSale.getUserId() != null && afterSale.getUserId().equals(userId)) {
            // 【P1-6】买家详情：回填 saleItems（买家上下文 merchantId 为 null，不涉及 myItems）。
            fillSaleItems(Collections.singletonList(afterSale));
            return afterSale;
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AfterSale cancelByBuyer(Long id, Long userId) {
        AfterSale afterSale = this.getById(id);
        if (afterSale == null || !userId.equals(afterSale.getUserId())) {
            throw new BusinessException(403, "无权操作该售后单");
        }
        if (!Objects.equals(afterSale.getStatus(), AfterSaleRule.STATUS_PENDING)) {
            throw new BusinessException(400, "仅「待处理」的售后单可以撤销");
        }
        // 原子条件更新：并发重复撤销时只有一次生效
        boolean updated = this.update(new LambdaUpdateWrapper<AfterSale>()
                .eq(AfterSale::getId, id)
                .eq(AfterSale::getStatus, AfterSaleRule.STATUS_PENDING)
                .set(AfterSale::getStatus, AfterSaleRule.STATUS_CANCELLED)
                .set(AfterSale::getFinishTime, LocalDateTime.now()));
        if (!updated) {
            throw new BusinessException(400, "售后单状态已变化，请刷新后重试");
        }
        log.info("买家撤销售后申请: afterSaleNo={}, userId={}", afterSale.getAfterSaleNo(), userId);
        return this.getById(id);
    }

    // ==================== 商家 / 管理员 ====================

    @Override
    public PageResult<AfterSale> getManagePage(Integer page, Integer size, Integer status) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : size;

        LambdaQueryWrapper<AfterSale> wrapper = new LambdaQueryWrapper<>();

        // 多租户：商家只能看到「订单里含自家商品」的售后单。
        // after_sale 无 merchant_id 列，经 order_item → product.merchant_id 反查。
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId != null) {
            List<Long> orderIds = orderMapper.selectOrderIdsByMerchantId(merchantId);
            if (orderIds.isEmpty()) {
                // ⚠️ 空集合必须提前返回：MyBatis-Plus 的 in() 收到空集合会拼出 IN () 语法错误
                // 【P1-3】空页也用夹取后的口径，避免响应回吐未经限制的原始 size。
                return new PageResult<>(0L, Collections.emptyList(),
                        (long) PageBounds.page(page), (long) PageBounds.size(size));
            }
            wrapper.in(AfterSale::getOrderId, orderIds);
        }

        if (status != null && status >= 0) {
            wrapper.eq(AfterSale::getStatus, status);
        }
        wrapper.orderByDesc(AfterSale::getId);

        Page<AfterSale> result = this.page(new Page<>(p, s), wrapper);
        enrich(result.getRecords());
        return new PageResult<>(result.getTotal(), result.getRecords(), result.getCurrent(), result.getSize());
    }

    /**
     * 为售后单批量补齐展示字段（商品明细 / 买家名 / 订单实付金额）。
     *
     * 商家处理售后时必须看到「退的是哪个商品、几件、买家是谁」，
     * 否则只能对着一个订单号猜。这里用 3 条批量查询代替逐单加载，避免 N+1。
     */
    private void enrich(List<AfterSale> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> orderIds = records.stream()
                .map(AfterSale::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return;
        }

        List<OrderItem> allItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds));
        Map<Long, List<OrderItem>> itemMap = allItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        Map<Long, Order> orderMap = orderMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, o -> o, (a, b) -> a));

        Set<Long> userIds = records.stream()
                .map(AfterSale::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> userMap = Collections.emptyMap();
        if (!userIds.isEmpty()) {
            userMap = userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(User::getId,
                            u -> u.getUsername() != null ? u.getUsername() : u.getNickname(),
                            (a, b) -> a));
        }

        for (AfterSale as : records) {
            as.setItems(itemMap.getOrDefault(as.getOrderId(), Collections.emptyList()));
            Order order = orderMap.get(as.getOrderId());
            if (order != null) {
                as.setOrderPayAmount(order.getPayAmount());
            }
            as.setBuyerName(userMap.get(as.getUserId()));
        }

        fillSaleItems(records);
    }

    /**
     * 回填「按商品售后」的明细：{@code saleItems}（整单商品明细）与商家视角的
     * {@code myItems}（本商家明细）。从 {@link #enrich(List)} 抽出，供详情接口
     * （{@link #getByIdWithPermission}）复用 —— 详情页 item_scope=1 时前端据
     * {@code saleItems} 渲染「售后商品明细」区块，此前详情未回填导致区块缺失。
     *
     * <p>【P1-6】一次 in 查询批量回填（避免 N+1）。
     * ⚠️ 空集合必须提前跳过 —— MyBatis-Plus 的 in() 收到空集合会拼出 IN () 语法错误
     * （同 getManagePage 里 orderIds 为空的处理）。item_scope=0 的记录保持 null。</p>
     */
    private void fillSaleItems(List<AfterSale> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        List<Long> afterSaleIds = records.stream()
                .map(AfterSale::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (!afterSaleIds.isEmpty()) {
            List<AfterSaleItem> allSaleItems = afterSaleItemMapper.selectList(
                    new LambdaQueryWrapper<AfterSaleItem>().in(AfterSaleItem::getAfterSaleId, afterSaleIds));
            Map<Long, List<AfterSaleItem>> saleItemMap = allSaleItems.stream()
                    .collect(Collectors.groupingBy(AfterSaleItem::getAfterSaleId));
            for (AfterSale as : records) {
                as.setSaleItems(saleItemMap.get(as.getId()));
            }
        }

        // 【P1-6】商家视角：仅对 item_scope=1 的记录额外回填 myItems（本商家明细），
        // **绝不动 items**（它永远是整单明细，是兼容红线）。
        // 一次 in 查询 + 内存分组，避免 N+1；空集合提前跳过（in() 空集会拼出 IN ()）。
        // 归属口径与 AfterSaleItemMapper.selectByAfterSaleAndMerchant 对齐：
        // 明细快照 merchant_id 或 product 当前 merchant_id 任一命中即认
        // （兜住「商品改过归属」与「商品被物理删除」两种历史情形）。
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId != null) {
            List<Long> itemScopeIds = records.stream()
                    .filter(r -> Integer.valueOf(AfterSaleRule.SCOPE_ITEM).equals(r.getItemScope()))
                    .map(AfterSale::getId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!itemScopeIds.isEmpty()) {
                List<AfterSaleItem> rows = afterSaleItemMapper.selectList(
                        new LambdaQueryWrapper<AfterSaleItem>().in(AfterSaleItem::getAfterSaleId, itemScopeIds));
                List<Long> productIds = rows.stream()
                        .map(AfterSaleItem::getProductId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                Map<Long, Long> merchantByProduct = new HashMap<>();
                if (!productIds.isEmpty()) {
                    for (Product product : productMapper.selectBatchIds(productIds)) {
                        if (product != null && product.getId() != null) {
                            merchantByProduct.put(product.getId(), product.getMerchantId());
                        }
                    }
                }
                Map<Long, List<AfterSaleItem>> mineMap = rows.stream()
                        .filter(it -> merchantId.equals(it.getMerchantId())
                                || merchantId.equals(merchantByProduct.get(it.getProductId())))
                        .collect(Collectors.groupingBy(AfterSaleItem::getAfterSaleId));
                for (AfterSale as : records) {
                    if (Integer.valueOf(AfterSaleRule.SCOPE_ITEM).equals(as.getItemScope())) {
                        as.setMyItems(mineMap.getOrDefault(as.getId(), Collections.emptyList()));
                    }
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AfterSale handle(Long id, String role, Long operatorId, AfterSaleHandleDTO dto) {
        AfterSale afterSale = this.getById(id);
        if (afterSale == null) {
            throw new BusinessException(400, "售后单不存在");
        }
        // 【2026-09-28】客服（kefu）刻意**不在此列**：客服的职责是超期前的裁定放行
        // （ServiceTicket 链路），而售后单的处理是商家与买家的履约环节。
        // 让客服同时掌握「放行」与「处理」会让两个角色边界糊掉。
        if (!MerchantContext.ROLE_ADMIN.equals(role) && !isAfterSaleOwnedByCurrentMerchant(afterSale)) {
            throw new BusinessException(403, "无权处理其他商家的售后单");
        }
        // 【P1-6 收口】订单级闸门对「按商品」售后单不够 —— 混合订单里商家 A 虽满足
        // 「订单含自家商品」，却可能处理的是商家 B 商品的售后（跨商户越权残余面）。
        // 故 item_scope=1 时追加明细级校验：该售后单的**全部明细**都必须属于当前商家，
        // 否则 403。item_scope=0（整单 / 历史）保持原订单级判定不变（兼容红线：
        // verify-after-sale.mjs 既有 403/200 断言走的正是 item_scope=0）。管理员豁免。
        if (!MerchantContext.ROLE_ADMIN.equals(role)
                && Integer.valueOf(AfterSaleRule.SCOPE_ITEM).equals(afterSale.getItemScope())
                && !isAfterSaleItemsAllOwnedByCurrentMerchant(afterSale)) {
            throw new BusinessException(403, "无权处理其他商家的商品售后");
        }
        String action = dto == null || dto.getAction() == null ? "" : dto.getAction().trim();
        if (action.isEmpty()) {
            throw new BusinessException(400, "缺少处理动作");
        }

        LocalDateTime now = LocalDateTime.now();
        switch (action) {
            case "agree" -> {
                // 仅退款：商家同意即视作退款完成；退货退款 / 换货：进入处理中，待寄回后确认完成
                int target = AfterSaleRule.TYPE_REFUND_ONLY == afterSale.getType()
                        ? AfterSaleRule.STATUS_FINISHED
                        : AfterSaleRule.STATUS_AGREED;
                boolean ok = this.update(new LambdaUpdateWrapper<AfterSale>()
                        .eq(AfterSale::getId, id)
                        .eq(AfterSale::getStatus, AfterSaleRule.STATUS_PENDING)
                        .set(AfterSale::getStatus, target)
                        .set(AfterSale::getAuditTime, now)
                        .set(AfterSale::getAuditRemark, dto.getRemark())
                        .set(AfterSale::getOperatorId, operatorId)
                        .set(target == AfterSaleRule.STATUS_FINISHED, AfterSale::getFinishTime, now));
                if (!ok) {
                    throw new BusinessException(400, "该售后单已被处理，请刷新后查看");
                }
                if (target == AfterSaleRule.STATUS_FINISHED
                        && AfterSaleRule.TYPE_RETURN_REFUND == afterSale.getType()) {
                    restoreStockForReturn(afterSale);
                }
            }
            case "reject" -> {
                if (dto.getRemark() == null || dto.getRemark().isBlank()) {
                    throw new BusinessException(400, "请填写拒绝原因");
                }
                boolean ok = this.update(new LambdaUpdateWrapper<AfterSale>()
                        .eq(AfterSale::getId, id)
                        .eq(AfterSale::getStatus, AfterSaleRule.STATUS_PENDING)
                        .set(AfterSale::getStatus, AfterSaleRule.STATUS_REJECTED)
                        .set(AfterSale::getAuditTime, now)
                        .set(AfterSale::getAuditRemark, dto.getRemark())
                        .set(AfterSale::getOperatorId, operatorId));
                if (!ok) {
                    throw new BusinessException(400, "该售后单已被处理，请刷新后查看");
                }
            }
            case "finish" -> {
                boolean ok = this.update(new LambdaUpdateWrapper<AfterSale>()
                        .eq(AfterSale::getId, id)
                        .eq(AfterSale::getStatus, AfterSaleRule.STATUS_AGREED)
                        .set(AfterSale::getStatus, AfterSaleRule.STATUS_FINISHED)
                        .set(AfterSale::getFinishTime, now)
                        .set(dto.getRemark() != null && !dto.getRemark().isBlank(),
                                AfterSale::getAuditRemark, dto.getRemark())
                        .set(AfterSale::getOperatorId, operatorId));
                if (!ok) {
                    throw new BusinessException(400, "仅「商家已同意」的售后单可以确认完成");
                }
                if (AfterSaleRule.TYPE_RETURN_REFUND == afterSale.getType()) {
                    restoreStockForReturn(afterSale);
                }
            }
            default -> throw new BusinessException(400, "不支持的处理动作：" + action);
        }

        log.info("售后单处理完成: afterSaleNo={}, action={}, operatorId={}",
                afterSale.getAfterSaleNo(), action, operatorId);
        return this.getById(id);
    }

    // ==================== 内部工具 ====================

    /**
     * 退货退款完成 → 回补库存。
     *
     * 【安全修复·P0-3】幂等必须按**订单**去重，而不是按售后单去重。
     * 原实现的"幂等"来自调用点的状态条件更新（要求前置状态为 待处理/已同意），
     * 这只能挡住「同一张售后单被重复 finish」，挡不住这条攻击链：
     *   申请退货退款 → 商家同意 → 完成（回补 1 次库存）
     *   → 售后单被撤销/拒绝后，规则允许在剩余时效内**重新申请** →
     *   再次走完整流程 → 库存又被回补一次 ← 可无限刷高库存。
     * 现增加订单级显式闸门：本订单此前已有 after_sale 类型的入库流水，
     * 说明库存已补过，直接跳过，不再重复回补。
     *
     * 说明：用 inventory_log 判重在此是安全的 —— after_sale 回补针对的是
     * 「已售出的商品」，其 inventory 记录必然已存在，addStock 必走
     * `else` 分支写流水；「首次入库」分支（不写流水）不可能出现在这条路径上。
     * 这与 deductStockForOrder 用 inventory_log 做幂等是同一套口径。
     *
     * 【P1-6 按商品粒度】在上面「订单级闸门」之外再补一层「订单×商品闸门」，
     * 形成先粗后细的两级幂等：
     *   路径 A（item_scope 为 null / 0）—— 整单兼容，与改造前逐字一致；
     *   路径 B（item_scope=1）—— 按 after_sale_item 逐商品回补，每个
     *     (order_id, product_id) 单独判重，从而支持「先退 A、再退 B」分别回补。
     * 注意：两级闸门都仍以 order_type='after_sale' 为幂等键（不能改成新类型，
     * 否则路径 A 的订单级闸门失效，会重新打开「整单补一次 + 按商品再补一次」的刷库存口子）。
     */
    private void restoreStockForReturn(AfterSale afterSale) {
        // ---- 路径 B：按商品（新）—— item_scope=1 且 after_sale_item 有明细 ----
        if (Integer.valueOf(AfterSaleRule.SCOPE_ITEM).equals(afterSale.getItemScope())) {
            List<AfterSaleItem> saleItems = afterSaleItemMapper.selectList(
                    new LambdaQueryWrapper<AfterSaleItem>()
                            .eq(AfterSaleItem::getAfterSaleId, afterSale.getId()));
            if (saleItems != null && !saleItems.isEmpty()) {
                for (AfterSaleItem saleItem : saleItems) {
                    // 第 2 层幂等（订单 + 商品）：该订单该商品已补过则跳过，
                    // 而不是像路径 A 那样「本订单补过一次就整体跳过」。
                    if (hasAfterSaleRestoreLog(afterSale.getOrderId(), saleItem.getProductId())) {
                        log.warn("退货回补库存跳过：该订单该商品已回补过, orderId={}, productId={}, itemId={}",
                                afterSale.getOrderId(), saleItem.getProductId(), saleItem.getId());
                        continue;
                    }
                    if (saleItem.getQuantity() == null || saleItem.getQuantity() <= 0) {
                        continue;
                    }
                    inventoryService.addStock(saleItem.getProductId(), saleItem.getQuantity(),
                            STOCK_LOG_TYPE_AFTER_SALE, afterSale.getOrderId());
                    // 【2026-10-10】库存回补的同时把销量对称扣回（净销量口径）。
                    // 走到这里说明「订单 + 商品」两层幂等闸门都放行了，与上面的
                    // addStock 严格同次同事务，不会出现「货退了销量还挂着」。
                    productMapper.deductSales(saleItem.getProductId(), saleItem.getQuantity());
                }
                log.info("退货退款完成，已按商品回补库存: afterSaleNo={}, orderId={}, items={}",
                        afterSale.getAfterSaleNo(), afterSale.getOrderId(), saleItems.size());
                return;
            }
            // 数据不一致兜底：标记为按商品却没有明细 ⇒ 退回整单语义（并留 WARN 便于发现）
            log.warn("退货回补库存：item_scope=1 但无明细，按整单回退, afterSaleId={}, orderId={}",
                    afterSale.getId(), afterSale.getOrderId());
        }

        // ---- 路径 A：整单兼容（item_scope 为 null / 0）—— 与改造前逐字一致 ----
        restoreWholeOrder(afterSale);
    }

    /**
     * 整单回补（兼容路径）—— 与改造前逐字一致：先做订单级幂等闸门，
     * 再取该订单全部明细逐条回补；明细为空则跳过。
     */
    private void restoreWholeOrder(AfterSale afterSale) {
        Long orderId = afterSale.getOrderId();

        // 订单级幂等闸门：本订单是否已回补过库存
        if (hasAfterSaleRestoreLog(orderId, null)) {
            log.warn("退货回补库存跳过：该订单已回补过, orderId={}, afterSaleNo={}",
                    orderId, afterSale.getAfterSaleNo());
            return;
        }

        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        if (items == null || items.isEmpty()) {
            log.warn("退货回补库存跳过：订单明细为空, orderId={}", orderId);
            return;
        }
        for (OrderItem item : items) {
            inventoryService.addStock(item.getProductId(), item.getQuantity(),
                    STOCK_LOG_TYPE_AFTER_SALE, orderId);
            // 【2026-10-10】与路径 B 同一条规则：库存回补 ⇒ 销量对称扣回。
            // 本循环已被上面的「订单级幂等闸门」挡住重复执行。
            productMapper.deductSales(item.getProductId(), item.getQuantity());
        }
        log.info("退货退款完成，已回补库存: afterSaleNo={}, orderId={}, items={}",
                afterSale.getAfterSaleNo(), orderId, items.size());
    }

    /**
     * 售后回补流水的存在性判断（幂等闸门）。
     *
     * @param orderId   订单 ID（必填）
     * @param productId {@code null} ⇒ 只按订单判重（路径 A 的订单级闸门）；
     *                  非 {@code null} ⇒ 按「订单 + 商品」判重（路径 B 的第 2 层闸门）
     */
    private boolean hasAfterSaleRestoreLog(Long orderId, Long productId) {
        LambdaQueryWrapper<InventoryLog> wrapper = new LambdaQueryWrapper<InventoryLog>()
                .eq(InventoryLog::getOrderId, orderId)
                .eq(InventoryLog::getOrderType, STOCK_LOG_TYPE_AFTER_SALE)
                .eq(InventoryLog::getType, 0);
        if (productId != null) {
            wrapper.eq(InventoryLog::getProductId, productId);
        }
        Long count = inventoryLogMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /** 判断某售后单是否属于当前登录商家（订单里含自家商品） */
    private boolean isAfterSaleOwnedByCurrentMerchant(AfterSale afterSale) {
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId == null) {
            return false;
        }
        return orderMapper.countOrderOwnedByMerchant(afterSale.getOrderId(), merchantId) > 0;
    }

    /**
     * 【P1-6】按商品售后（item_scope=1）的明细级归属判定：该售后单的全部明细
     * 是否都属于当前商家。
     *
     * 做法：用 {@code selectByAfterSaleAndMerchant} 取本商家明细，与 after_sale_item
     * 全量条数比对 —— 条数相等才认定「全部属于本商家」；本商家为空、或只占一部分
     * （混合归属）都判失败。这样商家 A 无法处理含商家 B 商品的售后单，
     * 混合归属的售后单只能由管理员处理（见改造方案 §3.3.4 的取舍）。
     *
     * 注意：本方法**只用于 item_scope=1**。item_scope=0 的整单语义仍走
     * {@link #isAfterSaleOwnedByCurrentMerchant}，不得收紧（兼容红线 C6）。
     */
    private boolean isAfterSaleItemsAllOwnedByCurrentMerchant(AfterSale afterSale) {
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId == null) {
            return false;
        }
        List<AfterSaleItem> mine = afterSaleItemMapper.selectByAfterSaleAndMerchant(afterSale.getId(), merchantId);
        if (mine == null || mine.isEmpty()) {
            return false;
        }
        Long total = afterSaleItemMapper.selectCount(new LambdaQueryWrapper<AfterSaleItem>()
                .eq(AfterSaleItem::getAfterSaleId, afterSale.getId()));
        return total != null && total.intValue() == mine.size();
    }

    private List<AfterSale> listByOrder(Long orderId) {
        if (orderId == null) {
            return Collections.emptyList();
        }
        return this.list(new LambdaQueryWrapper<AfterSale>().eq(AfterSale::getOrderId, orderId));
    }

    private List<AfterSale> listByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyList();
        }
        return this.list(new LambdaQueryWrapper<AfterSale>().in(AfterSale::getOrderId, orderIds));
    }

    private String generateAfterSaleNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "AS" + date + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
