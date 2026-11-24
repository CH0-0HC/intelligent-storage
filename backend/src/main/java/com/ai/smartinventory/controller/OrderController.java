package com.ai.smartinventory.controller;

import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.dto.OrderCreateDTO;
import com.ai.smartinventory.service.OrderService;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/order")
@Tag(name = "订单管理", description = "订单创建、查询、状态更新等接口")
public class OrderController {
    
    @Resource
    private OrderService orderService;
    
    @GetMapping("/list")
    public Result<PageResult<Order>> getOrderList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status) {
        
        Long userId = null;
        String role = MerchantContext.currentRole();
        
        if (StpUtil.isLogin()) {
            userId = StpUtil.getLoginIdAsLong();
        }
        
        PageResult<Order> result;
        
        // 【多商户隔离】admin 传 null → 全局视图；
        // merchant 也传 null，但 OrderServiceImpl 内部会依据会话中的 merchantId
        // 通过 order_item → product.merchant_id 反查，把范围限定在本商家订单内。
        // 原实现两类角色走的是完全相同的分支，商家实际能看到全平台订单。
        if (MerchantContext.ROLE_ADMIN.equals(role) || MerchantContext.ROLE_MERCHANT.equals(role)) {
            result = orderService.getOrderPage(null, page, size, status);
        } else {
            if (userId == null) {
                return Result.unauthorized("请先登录");
            }
            result = orderService.getOrderPage(userId, page, size, status);
        }
        
        return Result.success(result);
    }
    
    @GetMapping("/{id}")
    public Result<Order> getOrderById(@PathVariable Long id) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        Object roleObj = StpUtil.getSession().get("userRole");
        String role = roleObj != null ? roleObj.toString() : "user";
        
        Order order = orderService.getOrderByIdWithPermission(id, userId, role);
        
        if (order == null) {
            return Result.error("订单不存在或无权访问");
        }
        
        return Result.success(order);
    }
    
    @Operation(summary = "创建订单", description = "创建订单（含明细），自动扣减库存并清空购物车")
    // 【P1-1 限流补全】下单是写库存/写金额的敏感接口，此前完全无限流。
    @RateLimit(key = "order:create", permitsPerMinute = 20, permitsPerHour = 200)
    @PostMapping
    public Result<Order> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }

        // 【测试报告#2】收货人/电话/地址/明细的校验已全部下沉到 OrderCreateDTO 的
        // @NotBlank / @Pattern / @NotEmpty，由 @Valid 统一触发，GlobalExceptionHandler
        // 转成 400 并回吐 DTO 上的 message（文案与原先手写校验逐字一致）。
        // 这里原先那 4 段 if 已删除 —— 它们拦不住 "abc" 这种非法电话，是本次问题的成因。
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 【异常出口收口】不再 catch 后回吐 e.getMessage()：
        // 下单会触及库存原子扣减与事务，异常消息里可能带 SQL 细节。
        // 交由 GlobalExceptionHandler 分流：BusinessException 保留可读消息，其余脱敏。
        Order order = orderService.createOrderWithItems(
            userId,
            dto.getItems(),
            dto.getShippingName(),
            dto.getShippingPhone(),
            dto.getShippingAddress(),
            dto.getRemark()
        );
        return Result.success("订单创建成功，订单号: " + order.getOrderNo(), order);
    }
    
    @Operation(summary = "更新订单状态", description = "更新订单状态（发货/确认收货/取消）")
    // 【P1-1 限流补全】状态流转会触发扣库存/回补库存，限流防刷。
    @RateLimit(key = "order:status", permitsPerMinute = 30, permitsPerHour = 300)
    @PutMapping("/status/{id}")
    public Result<String> updateOrderStatus(@PathVariable Long id, @RequestParam Integer status) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        String role = MerchantContext.currentRole();
        if (role == null) {
            role = MerchantContext.ROLE_USER;
        }
        
        Order order = orderService.getOrderByIdWithPermission(id, userId, role);
        
        if (order == null) {
            return Result.error("订单不存在或无权访问");
        }
        
        if (MerchantContext.ROLE_ADMIN.equals(role) || MerchantContext.ROLE_MERCHANT.equals(role)) {
            // 【S-3 修复】管理员/商家的状态变更不再「任意跃迁」，改为显式校验合法迁移：
            //   取消：0 待付款 / 1 待发货 / 2 待收货 → 4（已完成 3 请走退货流程）
            //   发货：0 / 1 → 2（正式发货走 /order/ship/{id}，可录入物流信息）
            //   完成：2 待收货 → 3，且必须已付款（「已完成」是申请售后的唯一入口）
            // 非法值（如 5/99）、状态回退（3→2/1/0）一律拒绝。
            Integer cur = order.getOrderStatus();
            if (status == 4) {
                if (cur == null || cur == 3 || cur == 4) {
                    return Result.error("当前订单状态不允许取消（已完成订单请通过售后申请）");
                }
                // 记下「是被商家/管理员关闭的」：与用户自己取消、与系统超时自动取消
                // 在订单列表里要给出不同提示，否则用户会以为是系统把他的单子取消了。
                orderService.cancelOrder(order, OrderService.CANCEL_REASON_MERCHANT);
                return Result.success("订单已取消，库存已回补");
            }
            if (status == 2) {
                // 【S-4】原子推进(0/1→2) + 扣库存，同事务；并发只有一个能成功
                if (!orderService.advanceStatus(order.getId(), 2, 0, 1)) {
                    return Result.error("当前订单状态不允许发货");
                }
                return Result.success("状态更新成功");
            }
            if (status == 3) {
                // 「已完成」是售后唯一入口，必须已付款（与用户侧确认收货同一把关）
                if (order.getPayStatus() == null || order.getPayStatus() != 1) {
                    return Result.error("订单尚未完成付款，无法标记完成");
                }
                // 【S-4】原子推进(2→3) + 扣库存，同事务
                if (!orderService.advanceStatus(order.getId(), 3, 2)) {
                    return Result.error("仅待收货订单可标记完成");
                }
                return Result.success("状态更新成功");
            }
            return Result.error("不支持的订单状态变更");
        }

        // 普通用户操作
        // 1. 付款：待付款(0) → 已付款/待发货(1)
        if (status == 1) {
            if (order.getOrderStatus() != 0) {
                return Result.error("当前订单状态不允许付款");
            }
            if (!order.getUserId().equals(userId)) {
                return Result.forbidden("无权操作此订单");
            }
            // 【S-5 修复】上面两条内存判断只负责给出可读的错误文案，**不是**并发防线。
            // 原实现是 setOrderStatus(1) + updateById(order) 整实体写回，判定与写入之间
            // 没有任何数据库闸门：超时任务刚把订单改成 4（已取消），这次付款会无条件写回 1，
            // 产出一张「显示已付款、却从未扣过库存」的幽灵单。
            // 真正的闸门下沉到 markPaid —— 它把「当前必须仍是待付款(0)」写进 WHERE，
            // 与超时取消侧的收紧条件对称，两条竞态一并关闭。
            if (!orderService.markPaid(order.getId())) {
                return Result.error("订单状态已变化，付款失败（可能已被取消或已完成）");
            }
            log.info("用户付款成功: orderNo={}, userId={}, amount={}", order.getOrderNo(), userId, order.getPayAmount());
            return Result.success("付款成功");
        }
        // 2. 确认收货：待收货(2) → 已完成(3)
        if (status == 3) {
            if (order.getOrderStatus() != 2) {
                return Result.error("当前订单状态不允许确认收货");
            }
            if (!order.getUserId().equals(userId)) {
                return Result.forbidden("无权操作此订单");
            }
            // 【R15 修复·2026-09-25】未付款订单不得确认收货。
            // 「已完成(3)」是申请售后的**唯一**入口状态，而售后会真的把钱退出去，
            // 因此必须在这条路径上把住付款关。商家侧的「发货」守卫仍保留 0/1
            // （那处放宽是为修「待付款订单无法发货」而做的，属演示需要），
            // 所以本校验是唯一能挡住「未付钱走完全流程」的地方。
            // 注意：payStatus 是 Integer，判空 + != 1 两个条件都要写
            //（历史数据可能是 NULL，只写 != 1 会把 NULL 放过去语义不清）。
            if (order.getPayStatus() == null || order.getPayStatus() != 1) {
                return Result.error("订单尚未完成付款，无法确认收货");
            }
            // 【S-4】原子推进(2→3) + 扣库存，同事务；并发重复点击只有一个成功，
            // 保证「发货或签收后库存一定已扣且只扣一次」。
            if (!orderService.advanceStatus(order.getId(), 3, 2)) {
                return Result.error("当前订单状态不允许确认收货");
            }
            log.info("用户确认收货: orderNo={}, userId={}", order.getOrderNo(), userId);
            return Result.success("确认收货成功");
        }
        // 3. 取消订单
        if (status == 4) {
            if (!order.getUserId().equals(userId)) {
                return Result.forbidden("无权操作此订单");
            }
            // 【S-3】已完成(3)订单不能直接取消，应走售后流程，避免误回补库存
            if (order.getOrderStatus() != null && order.getOrderStatus() == 3) {
                return Result.error("已完成订单不能取消，请通过售后申请");
            }
            orderService.cancelOrder(order, OrderService.CANCEL_REASON_USER);
            return Result.success("订单已取消，库存已回补");
        }

        return Result.forbidden("仅管理员和商家可以修改订单状态");
    }
    
    @Operation(summary = "商家发货", description = "录入物流公司和快递单号，订单状态变更为待收货")
    // 【修复】补 mode = SaMode.OR。
    // @SaCheckRole.mode() 默认是 SaMode.AND（要求同时具备列举的**全部**角色），
    // 而 sys_user.role 是单值字段 —— admin 与 merchant 都不可能同时满足，
    // 一旦注册 SaInterceptor 使注解真正生效，本接口会对所有角色返回 403，发货功能整体失效。
    // 全库另外 18 处多角色注解都带了 OR，仅此处漏写，属遗漏而非设计。
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 【P1-1 限流补全】发货扣库存，限流防刷。
    @RateLimit(key = "order:ship", permitsPerMinute = 30, permitsPerHour = 300)
    @PutMapping("/ship/{id}")
    public Result<String> shipOrder(@PathVariable Long id, @RequestBody ShipDTO dto) {
        Order order = orderService.getById(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        // 【多商户隔离】发货是商家最敏感的写操作，必须确认订单里有本商家的商品，
        // 否则商家 A 可以给商家 B 的订单填一个假物流单号，把对方订单推进到「待收货」。
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId != null && !orderService.isOrderOwnedByMerchant(id, merchantId)) {
            return Result.forbidden("无权操作其他商家的订单");
        }
        // 【S-3/S-4 修复】状态校验与库存扣减下沉到 advanceStatus：
        // 它用条件更新作并发闸门（仅 0/1 → 2 能成功），并与扣库存处于同一事务。
        if (dto.getLogisticsCompany() == null || dto.getLogisticsCompany().isBlank()) {
            return Result.error(400, "请输入物流公司");
        }
        if (dto.getTrackingNo() == null || dto.getTrackingNo().isBlank()) {
            return Result.error(400, "请输入物流单号");
        }

        // 原子推进(0/1→2) + 扣库存（同事务）；并发双击发货只有一个成功，不会重复扣库存。
        if (!orderService.advanceStatus(order.getId(), 2, 0, 1)) {
            return Result.error("当前订单状态不允许发货");
        }
        // 回填物流信息：只更新这两个字段，避免 updateById 把 order 对象里的旧状态写回。
        orderService.update(new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, order.getId())
                .set(Order::getLogisticsCompany, dto.getLogisticsCompany())
                .set(Order::getTrackingNo, dto.getTrackingNo()));

        log.info("商家发货: orderNo={}, company={}, trackingNo={}", order.getOrderNo(), dto.getLogisticsCompany(), dto.getTrackingNo());
        return Result.success("发货成功，物流信息已录入");
    }
    
    @Operation(summary = "删除订单（管理员）", description = "管理员逻辑删除订单")
    // 【P1-1 限流补全】删除订单是破坏性操作。
    @RateLimit(key = "order:delete", permitsPerMinute = 20, permitsPerHour = 100)
    @DeleteMapping("/{id}")
    public Result<String> deleteOrder(@PathVariable Long id) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        Object roleObj = StpUtil.getSession().get("userRole");
        String role = roleObj != null ? roleObj.toString() : "user";
        
        Order order = orderService.getOrderByIdWithPermission(id, userId, role);
        
        if (order == null) {
            return Result.error("订单不存在或无权访问");
        }
        
        if (!"admin".equals(role)) {
            return Result.forbidden("仅管理员可以删除订单");
        }
        
        order.setDeleted(1);
        boolean success = orderService.updateById(order);
        
        if (success) {
            return Result.success("删除成功");
        } else {
            return Result.error(500, "删除失败");
        }
    }
    
    /**
     * 下单入参已合并回 {@code com.ai.smartinventory.dto.OrderCreateDTO}。
     *
     * 这里原来有一个同名 static 内部类，它**遮蔽**了 dto 包里的同名类 ——
     * 实际生效的是内部类，dto 包那份一直是死代码（在它上面加校验不会生效）。
     * 现已删除内部类并改由 import 引入，避免后人在错误的文件上修校验。
     *
     * 【安全修复·P0-1】入参不含 totalAmount / discountAmount：
     * 订单金额一律由服务端按 productId 回查 product 表后计算，
     * 客户端只能决定「买什么、买几件」，无法再左右价格。
     */
    @Data
    public static class ShipDTO {
        private String logisticsCompany;
        private String trackingNo;
    }
}