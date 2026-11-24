package com.ai.smartinventory.ai;

import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.entity.Inventory;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.mapper.InventoryLogMapper;
import com.ai.smartinventory.service.InventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class InventoryWarningAnalyzer {
    
    @Resource
    private InventoryService inventoryService;
    
    @Resource
    private ProductMapper productMapper;
    
    @Resource
    private InventoryLogMapper inventoryLogMapper;
    
    @Resource
    private AiFacade aiFacade;
    
    /**
     * 库存预警分析。
     *
     * 【租户隔离（2026-09-22 补全）】原实现直接 `inventoryService.list()` 取**全平台**库存，
     * 于是 /ai/inventory/warning 对商家返回所有商户的库存水位与畅销情况 ——
     * 与本批次刚收敛的 /inventory/warning（走 InventoryServiceImpl.getWarningInventories）
     * 口径矛盾：同一个「库存预警」概念，两个接口给出两套数据范围。
     * 现统一按会话 merchantId 收敛，管理员保持全局视图。
     *
     * 注意这里**不能**改用 inventoryService.getWarningInventories()：
     * 那条 SQL 已自带 `quantity <= warning_quantity` 过滤，只返回「预警」一项，
     * 而本方法还要据此算出「积压（overstock）」清单，必须拿到全量库存行。
     * 因此改为「先取本商家商品 id 集合，再用 IN 收敛」——
     * 与 InventoryLogServiceImpl.getLogPage 的写法保持一致。
     */
    public InventoryWarningResult analyze() {
        List<Inventory> allInventories = loadScopedInventories();
        List<Inventory> warningList = new ArrayList<>();
        List<Inventory> overstockList = new ArrayList<>();
        
        for (Inventory inventory : allInventories) {
            if (inventory.getDeleted() == 1) continue;
            
            if (inventory.getQuantity() <= inventory.getWarningQuantity()) {
                warningList.add(inventory);
            } else if (inventory.getQuantity() > inventory.getWarningQuantity() * 10) {
                overstockList.add(inventory);
            }
        }
        
        List<ReplenishmentItem> replenishments = generateReplenishmentRecommendations(warningList);
        
        String aiAdvice = getAiAdvice(warningList, overstockList);
        
        return new InventoryWarningResult(warningList, overstockList, replenishments, aiAdvice);
    }
    
    /**
     * 当前商家可见的库存行：管理员 / 未登录返回全量，商家只返回自己名下商品的库存。
     *
     * ⚠️ 商家名下无商品时必须**提前返回空列表**，不能把空集合直接交给 in() ——
     * MyBatis-Plus 会拼出 `IN ()`，在 MySQL 里是语法错误。
     */
    private List<Inventory> loadScopedInventories() {
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId == null) {
            return inventoryService.list();
        }
        List<Long> ownedProductIds = productMapper.selectList(
                        new LambdaQueryWrapper<Product>()
                                .select(Product::getId)
                                .eq(Product::getMerchantId, merchantId))
                .stream()
                .map(Product::getId)
                .toList();
        if (ownedProductIds.isEmpty()) {
            return List.of();
        }
        return inventoryService.list(
                new LambdaQueryWrapper<Inventory>().in(Inventory::getProductId, ownedProductIds));
    }
    
    private List<ReplenishmentItem> generateReplenishmentRecommendations(List<Inventory> warningList) {
        List<ReplenishmentItem> recommendations = new ArrayList<>();
        
        for (Inventory inventory : warningList) {
            Product product = productMapper.selectById(inventory.getProductId());
            if (product == null) continue;
            
            int avgDailySales = calculateAvgDailySales(inventory.getProductId(), 30);
            int daysUntilStockout = avgDailySales > 0 ? inventory.getQuantity() / avgDailySales : 999;
            
            int recommendedQuantity = Math.max(avgDailySales * 7 - inventory.getQuantity(), 10);
            
            ReplenishmentItem item = new ReplenishmentItem();
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setCurrentStock(inventory.getQuantity());
            item.setWarningQuantity(inventory.getWarningQuantity());
            item.setAvgDailySales(avgDailySales);
            item.setDaysUntilStockout(daysUntilStockout);
            item.setRecommendedQuantity(recommendedQuantity);
            item.setEstimatedCost(product.getCost().multiply(BigDecimal.valueOf(recommendedQuantity)));
            item.setPriority(daysUntilStockout < 3 ? "紧急" : daysUntilStockout < 7 ? "重要" : "一般");
            
            recommendations.add(item);
        }
        
        recommendations.sort(Comparator.comparingInt(ReplenishmentItem::getDaysUntilStockout));
        return recommendations;
    }
    
    private int calculateAvgDailySales(Long productId, int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);
        List<InventoryLog> logs = inventoryLogMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InventoryLog>()
                .eq(InventoryLog::getProductId, productId)
                .eq(InventoryLog::getType, 1)
                .ge(InventoryLog::getCreateTime, startTime)
        );
        
        int totalSales = logs.stream().mapToInt(InventoryLog::getQuantity).sum();
        return totalSales / days;
    }
    
    private String getAiAdvice(List<Inventory> warningList, List<Inventory> overstockList) {
        if (warningList.isEmpty() && overstockList.isEmpty()) {
            return "库存状况良好，无需特别建议。";
        }
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("作为库存管理专家，请分析以下库存状况并给出优化建议：\n\n");
        
        if (!warningList.isEmpty()) {
            prompt.append("【库存不足商品】\n");
            for (Inventory inv : warningList) {
                Product product = productMapper.selectById(inv.getProductId());
                if (product != null) {
                    prompt.append(String.format("- %s: 当前库存%d, 预警值%d\n", 
                        product.getName(), inv.getQuantity(), inv.getWarningQuantity()));
                }
            }
        }
        
        if (!overstockList.isEmpty()) {
            prompt.append("\n【库存积压商品】\n");
            for (Inventory inv : overstockList) {
                Product product = productMapper.selectById(inv.getProductId());
                if (product != null) {
                    prompt.append(String.format("- %s: 当前库存%d, 预警值%d\n", 
                        product.getName(), inv.getQuantity(), inv.getWarningQuantity()));
                }
            }
        }
        
        prompt.append("\n请给出具体的补货建议和促销建议，帮助优化库存结构。");
        
        return aiFacade.generateText(prompt.toString());
    }
    
    @Data
    public static class InventoryWarningResult {
        private List<Inventory> warningInventories;
        private List<Inventory> overstockInventories;
        private List<ReplenishmentItem> replenishments;
        private String aiAdvice;
        private LocalDateTime generateTime;
        
        public InventoryWarningResult(List<Inventory> warningInventories, 
                                     List<Inventory> overstockInventories,
                                     List<ReplenishmentItem> replenishments,
                                     String aiAdvice) {
            this.warningInventories = warningInventories;
            this.overstockInventories = overstockInventories;
            this.replenishments = replenishments;
            this.aiAdvice = aiAdvice;
            this.generateTime = LocalDateTime.now();
        }
    }
    
    @Data
    public static class ReplenishmentItem {
        private Long productId;
        private String productName;
        private Integer currentStock;
        private Integer warningQuantity;
        private Integer avgDailySales;
        private Integer daysUntilStockout;
        private Integer recommendedQuantity;
        private BigDecimal estimatedCost;
        private String priority;
    }
}
