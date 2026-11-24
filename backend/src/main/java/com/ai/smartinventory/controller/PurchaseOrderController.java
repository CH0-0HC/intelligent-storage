package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.MoneyBounds;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.entity.PurchaseOrder;
import com.ai.smartinventory.service.PurchaseOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 采购单控制器。
 *
 * 【多租户边界（2026-09-22 补全）】
 * 此前本类只有 addPurchaseOrder 绑定了 merchantId，其余 6 个端点都是
 * 「getById(id) → 判断存在 → 直接操作」，**从不校验归属** —— 任何商家只要猜到 id，
 * 就能读取、修改、删除、审核（含审核通过）、完成、取消**其他商户**的采购单。
 * 由于上一轮已让 @SaCheckRole 真正生效，「同角色跨商户」成了仅剩的越权通道，
 * 因此本类每个端点都在载入后调用 MerchantContext.assertCanAccessMerchantData()。
 *
 * 语义：管理员（merchantId 为 null）拥有全局视图，放行；商家只能操作 merchant_id
 * 等于自己的单。历史上 merchant_id 为 NULL 的采购单对商家一律 403 —— 这是**预期行为**
 * （把 NULL 视为「公开」会让任意商家互访彼此的旧数据，等于重新打开越权）。
 *
 * 【异常出口（2026-09-22 收口）】
 * 此前 8 个方法都包着 `catch (Exception e) { return Result.error("...：" + e.getMessage()); }`，
 * 把原始 SQL 与表结构直接回吐给前端（实测空 body 建单时返回整段
 * `### Error updating database ... INSERT INTO purchase_order (...)`）。
 * 现全部去掉，异常统一上抛给 GlobalExceptionHandler：BusinessException 保留可读消息，
 * 其余脱敏为「系统繁忙，请稍后重试」。
 */
@Slf4j
@SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
@RestController
@RequestMapping("/purchase")
public class PurchaseOrderController {
    
    @Resource
    private PurchaseOrderService purchaseOrderService;
    
    @GetMapping("/list")
    public Result<Object> getPurchaseOrderList(@RequestParam Map<String, Object> params) {
        log.info("查询采购订单列表，参数: {}", params);
        Object result = purchaseOrderService.getPurchaseOrderPage(params);
        log.info("查询采购订单列表成功");
        return Result.success(result);
    }
    
    @GetMapping("/{id}")
    public Result<PurchaseOrder> getPurchaseOrderById(@PathVariable Long id) {
        log.info("查询采购订单详情，ID: {}", id);
        PurchaseOrder order = purchaseOrderService.getById(id);
        if (order == null || order.getDeleted() == 1) {
            log.warn("采购订单不存在，ID: {}", id);
            return Result.error("采购订单不存在");
        }
        // 【多租户边界】读也要校验：否则商家可凭 id 遍历读取其他商户的采购单
        MerchantContext.assertCanAccessMerchantData(order.getMerchantId());
        log.info("查询采购订单详情成功，订单号: {}", order.getOrderNo());
        return Result.success(order);
    }
    
    // 【P1-1 限流补全】采购单写操作原先无限流。
    @RateLimit(key = "purchase:add", permitsPerMinute = 30, permitsPerHour = 300)
    @PostMapping
    public Result<String> addPurchaseOrder(@RequestBody PurchaseOrder order) {
        log.info("创建采购订单，供应商: {}", order.getSupplierName());

        // 【参数校验】supplier_name 是 NOT NULL 列，此前不校验会一路走到 SQL 层报
        // 「Field 'supplier_name' doesn't have a default value」，且原始报错还会回吐前端。
        if (order.getSupplierName() == null || order.getSupplierName().isBlank()) {
            return Result.error(400, "请填写供应商名称");
        }
        // 【P1-2】采购总额此前完全不校验，可传负数/超大值（decimal(12,2) 截断）。
        // 【2026-09-28 修复】原实现误用 validateNonNegative（decimal(10,2) 口径），
        // 把采购总额卡在 99,999,999.99 —— 而 purchase_order.total_amount 是
        // decimal(12,2)，真实上限 99,999,999,999.99，属「校验比 schema 更严」的假上限。
        // 改走 validateTotalAmount，与目标列精度对齐（单价仍走 MAX_AMOUNT 不动）。
        String amountErr = MoneyBounds.validateTotalAmount(order.getTotalAmount(), "采购总额");
        if (amountErr != null) {
            return Result.error(400, amountErr);
        }

        String orderNo = generateOrderNo();
        order.setOrderNo(orderNo);
        order.setStatus(0);
        order.setDeleted(0);

        // 【多租户隔离】与 ProductController.addProduct 同一约定：
        // 商家新建采购单时强制绑定为「当前登录商家」，忽略请求体传入的
        // merchantId —— 否则商家可把采购单挂到别人名下，或伪造归属。
        // 管理员不覆盖（可代任意商户建单）。
        // 背景：此前这里既不写 merchantId，列表查询也不按 merchantId 过滤，
        // 采购模块整体漏掉了第三轮做的多租户隔离。
        Long currentMerchantId = MerchantContext.currentMerchantId();
        if (currentMerchantId != null) {
            order.setMerchantId(currentMerchantId);
        }

        boolean success = purchaseOrderService.save(order);
        if (success) {
            log.info("创建采购订单成功，订单号: {}", orderNo);
            return Result.success("创建成功，订单号: " + orderNo);
        }
        log.error("创建采购订单失败");
        return Result.error(500, "创建失败");
    }
    
    @RateLimit(key = "purchase:update", permitsPerMinute = 30, permitsPerHour = 300)
    @PutMapping
    public Result<String> updatePurchaseOrder(@RequestBody PurchaseOrder order) {
        log.info("更新采购订单，ID: {}", order.getId());
        PurchaseOrder existing = purchaseOrderService.getById(order.getId());
        if (existing == null || existing.getDeleted() == 1) {
            log.warn("采购订单不存在，ID: {}", order.getId());
            return Result.error("采购订单不存在");
        }

        // 【多租户边界】写操作必须校验归属
        MerchantContext.assertCanAccessMerchantData(existing.getMerchantId());
        // 【防篡改归属】商家不允许通过请求体把采购单改挂到别的商户名下
        // （与 ProductController.updateProduct 同一做法）
        if (MerchantContext.isMerchant()) {
            order.setMerchantId(existing.getMerchantId());
        }
        // 【P1-2】更新同样校验金额范围（null 表示「不修改」，校验方法放行）。
        // 【2026-09-28】同新增路径：改用汇总金额口径（decimal(12,2)），不再卡 1 亿。
        String amountErr = MoneyBounds.validateTotalAmountIfPresent(order.getTotalAmount(), "采购总额");
        if (amountErr != null) {
            return Result.error(400, amountErr);
        }

        boolean success = purchaseOrderService.updateById(order);
        if (success) {
            log.info("更新采购订单成功，ID: {}", order.getId());
            return Result.success("更新成功");
        }
        log.error("更新采购订单失败，ID: {}", order.getId());
        return Result.error(500, "更新失败");
    }
    
    @RateLimit(key = "purchase:delete", permitsPerMinute = 20, permitsPerHour = 100)
    @DeleteMapping("/{id}")
    public Result<String> deletePurchaseOrder(@PathVariable Long id) {
        log.info("删除采购订单，ID: {}", id);
        PurchaseOrder order = purchaseOrderService.getById(id);
        if (order == null || order.getDeleted() == 1) {
            log.warn("采购订单不存在，ID: {}", id);
            return Result.error("采购订单不存在");
        }

        // 【多租户边界】删除是破坏性操作，必须校验归属
        MerchantContext.assertCanAccessMerchantData(order.getMerchantId());

        boolean success = purchaseOrderService.removeById(id);
        if (success) {
            log.info("删除采购订单成功，ID: {}", id);
            return Result.success("删除成功");
        }
        log.error("删除采购订单失败，ID: {}", id);
        return Result.error(500, "删除失败");
    }
    
    @RateLimit(key = "purchase:audit", permitsPerMinute = 30, permitsPerHour = 200)
    @PutMapping("/audit/{id}")
    public Result<String> auditPurchaseOrder(@PathVariable Long id, @RequestParam Integer status, @RequestParam(required = false) String auditRemark) {
        log.info("审核采购订单，ID: {}, 审核结果: {}", id, status);
        PurchaseOrder order = purchaseOrderService.getById(id);
        if (order == null || order.getDeleted() == 1) {
            log.warn("采购订单不存在，ID: {}", id);
            return Result.error("采购订单不存在");
        }

        // 【多租户边界】审核是最高危项：不校验归属时，商家可「审核通过」其他商户的采购单
        MerchantContext.assertCanAccessMerchantData(order.getMerchantId());

        if (order.getStatus() != 0) {
            log.warn("采购订单状态不正确，当前状态: {}", order.getStatus());
            return Result.error("只能审核待审核状态的订单");
        }

        // 【测试报告#8】审核备注必填。原先 auditRemark 为 null 时静默回落成
        // 「审核通过/不通过」、为空串时更是原样入库 —— 于是「确认审核」什么都不填
        // 也能一路「操作成功」，事后无从追溯依据。
        // 刻意放在**归属校验与状态校验之后**：既有的越权探测脚本期望的是 403
        // （改挂他人采购单），必须让它先命中归属校验而不是被 400 截胡。
        if (auditRemark == null || auditRemark.isBlank()) {
            return Result.error(400, "请填写审核备注");
        }

        order.setStatus(status);
        order.setAuditRemark(auditRemark.trim());

        boolean success = purchaseOrderService.updateById(order);
        if (success) {
            String result = status == 1 ? "审核通过" : "审核不通过";
            log.info("审核采购订单成功，ID: {}, 结果: {}", id, result);
            return Result.success(result);
        }
        log.error("审核采购订单失败，ID: {}", id);
        return Result.error(500, "审核失败");
    }
    
    @RateLimit(key = "purchase:complete", permitsPerMinute = 30, permitsPerHour = 200)
    @PutMapping("/complete/{id}")
    public Result<String> completePurchaseOrder(@PathVariable Long id) {
        log.info("确认完成采购订单，ID: {}", id);
        PurchaseOrder order = purchaseOrderService.getById(id);
        if (order == null || order.getDeleted() == 1) {
            log.warn("采购订单不存在，ID: {}", id);
            return Result.error("采购订单不存在");
        }

        // 【多租户边界】状态流转同样是写操作
        MerchantContext.assertCanAccessMerchantData(order.getMerchantId());

        if (order.getStatus() != 1) {
            log.warn("采购订单状态不正确，当前状态: {}", order.getStatus());
            return Result.error("只能完成审核通过状态的订单");
        }

        order.setStatus(2);

        boolean success = purchaseOrderService.updateById(order);
        if (success) {
            log.info("确认完成采购订单成功，ID: {}", id);
            return Result.success("已确认完成");
        }
        log.error("确认完成采购订单失败，ID: {}", id);
        return Result.error(500, "确认失败");
    }
    
    @RateLimit(key = "purchase:cancel", permitsPerMinute = 30, permitsPerHour = 200)
    @PutMapping("/cancel/{id}")
    public Result<String> cancelPurchaseOrder(@PathVariable Long id) {
        log.info("取消采购订单，ID: {}", id);
        PurchaseOrder order = purchaseOrderService.getById(id);
        if (order == null || order.getDeleted() == 1) {
            log.warn("采购订单不存在，ID: {}", id);
            return Result.error("采购订单不存在");
        }

        // 【多租户边界】
        MerchantContext.assertCanAccessMerchantData(order.getMerchantId());

        if (order.getStatus() != 0) {
            log.warn("采购订单状态不正确，当前状态: {}", order.getStatus());
            return Result.error("只能取消待审核状态的订单");
        }

        order.setStatus(4);
        order.setAuditRemark("用户取消");

        boolean success = purchaseOrderService.updateById(order);
        if (success) {
            log.info("取消采购订单成功，ID: {}", id);
            return Result.success("已取消");
        }
        log.error("取消采购订单失败，ID: {}", id);
        return Result.error(500, "取消失败");
    }
    
    private String generateOrderNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long timestamp = System.currentTimeMillis();
        return "PO" + date + timestamp % 10000;
    }
}
