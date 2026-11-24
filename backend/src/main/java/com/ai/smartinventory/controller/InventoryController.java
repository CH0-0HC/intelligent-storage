package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.Inventory;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.service.InventoryService;
import com.ai.smartinventory.service.InventoryLogService;
import com.ai.smartinventory.service.ProductService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import java.util.List;

/**
 * 库存控制器。
 *
 * 【多租户边界（2026-09-22 补全）】
 * 本类此前**完全没有** MerchantContext 调用 —— 商家能看到全平台库存/预警/流水，
 * 并对他人的 productId 任意出入库。现分工如下：
 *   - 查询类（list / warning / log）：在 service 内部按会话 merchantId 收敛 SQL
 *     （见 InventoryServiceImpl / InventoryLogServiceImpl）；
 *   - 出入库（in / out）：在**本控制器**做商品归属断言。
 *
 * 为什么归属断言必须放在控制器而不是 service —— 两条硬理由：
 *   1. `InventoryService.addStock/reduceStock` 是**共享路径**：`OrderServiceImpl`
 *      的下单扣减（reduceStock）与取消回补（addStock）也走它，操作者是**普通用户**，
 *      不能要求「必须拥有该商品」，否则下单链路会被一起锁死。
 *   2. `MerchantContext` 依赖 Sa-Token 的 Web 上下文；写进 service 会让
 *      `InventoryServiceImplTest`（纯 Mockito、无 Web 上下文）在调用 addStock 时抛错。
 * 这与既有 `ProductController.updateProduct` 的做法一致。
 *
 * 【异常出口（2026-09-22 收口）】
 * 原先 3 个方法都用 `catch (Exception e) { return Result.error(e.getMessage()); }`
 * 把原始异常消息直接回吐前端，现全部去掉，统一交给 GlobalExceptionHandler 脱敏。
 */
@Slf4j
@SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
@RestController
@RequestMapping("/inventory")
public class InventoryController {
    
    @Resource
    private InventoryService inventoryService;
    
    @Resource
    private InventoryLogService inventoryLogService;

    @Resource
    private ProductService productService;
    
    @GetMapping("/list")
    public Result<PageResult<Inventory>> getInventoryList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        
        PageQueryDTO params = new PageQueryDTO();
        params.setPage(page);
        params.setSize(size);
        params.setKeyword(keyword);
        
        return Result.success(inventoryService.getInventoryPage(params));
    }
    
    @GetMapping("/warning")
    public Result<List<Inventory>> getWarningInventories() {
        return Result.success(inventoryService.getWarningInventories());
    }
    
    @GetMapping("/log/list")
    public Result<PageResult<InventoryLog>> getLogList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        
        PageQueryDTO params = new PageQueryDTO();
        params.setPage(page);
        params.setSize(size);
        
        return Result.success(inventoryLogService.getLogPage(params));
    }
    
    // 【P1-1 限流补全】手工入库/出库直接改库存，限流防刷。
    @RateLimit(key = "inventory:in", permitsPerMinute = 60, permitsPerHour = 500)
    @PostMapping("/in")
    public Result<String> stockIn(@RequestBody StockInDTO dto) {
        if (dto.getProductId() == null) {
            return Result.error(400, "请指定商品");
        }
        assertProductAccessible(dto.getProductId());

        inventoryService.addStock(dto.getProductId(), dto.getQuantity(),
                dto.getType(), dto.getOrderId());
        return Result.success("入库成功");
    }
    
    // 【P1-1 限流补全】手工出库直接改库存，限流防刷。
    @RateLimit(key = "inventory:out", permitsPerMinute = 60, permitsPerHour = 500)
    @PostMapping("/out")
    public Result<String> stockOut(@RequestBody StockOutDTO dto) {
        if (dto.getProductId() == null) {
            return Result.error(400, "请指定商品");
        }
        assertProductAccessible(dto.getProductId());

        inventoryService.reduceStock(dto.getProductId(), dto.getQuantity(),
                dto.getType(), dto.getOrderId());
        return Result.success("出库成功");
    }
    
    // 【P1-1 限流补全】全量同步是重操作（扫全表），收紧到低频。
    @RateLimit(key = "inventory:sync", permitsPerMinute = 3, permitsPerHour = 20)
    @PostMapping("/sync")
    public Result<InventoryService.SyncStockResult> syncStock() {
        // 商家只同步自己名下商品，管理员全量同步（见 InventoryServiceImpl.syncStock）
        InventoryService.SyncStockResult result = inventoryService.syncStock();
        return Result.success(result);
    }

    /**
     * 商品归属断言：管理员放行；商家只能操作自己名下的商品。
     *
     * 对**不存在**的商品同样先做归属判定（product 为 null 时对商家一律 403）——
     * 这是刻意的：不向非归属方区分「此 id 不存在」与「此 id 属于别人」，
     * 避免被用来枚举全平台的商品 id。
     */
    private void assertProductAccessible(Long productId) {
        Product product = productService.getById(productId);
        MerchantContext.assertCanAccessMerchantData(product == null ? null : product.getMerchantId());
    }
    
    @Data
    public static class StockInDTO {
        private Long productId;
        private Integer quantity;
        private String type;
        private Long orderId;
    }
    
    @Data
    public static class StockOutDTO {
        private Long productId;
        private Integer quantity;
        private String type;
        private Long orderId;
    }
}
