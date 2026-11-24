package com.ai.smartinventory.ai;

import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.entity.*;
import com.ai.smartinventory.mapper.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BusinessReportGenerator {
    
    @Resource
    private OrderMapper orderMapper;
    
    @Resource
    private ProductMapper productMapper;
    
    @Resource
    private InventoryMapper inventoryMapper;
    
    @Resource
    private AiFacade aiFacade;
    
    // 【已删除】原先还注入了 InventoryLogMapper，但本类全无调用（死依赖），已移除 ——
    // 与 InventoryServiceImpl 里那个 productService 是同一类问题。
    // ⚠️ 删这类字段时务必**连字段声明一起删**：只删 import 会留下编译错误。
    
    public BusinessReport generateReport(String period) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate;
        
        switch (period) {
            case "week" -> startDate = endDate.minusDays(7);
            case "month" -> startDate = endDate.minusDays(30);
            case "quarter" -> startDate = endDate.minusDays(90);
            default -> startDate = endDate.minusDays(30);
        }
        
        // 【租户隔离（2026-09-22 补全）】本报告此前三处取数（订单、热销商品、库存预警）
        // 全部是全平台的。配合已真正生效的 @SaCheckRole，商家调用 /ai/report 会拿到
        // **其他商户**的销售额、利润与订单量 —— 与第三轮修掉的 /ai/dashboard 泄露同型。
        // 现统一按会话 merchantId 收敛，报告语义变为「本商家的经营报告」；
        // 管理员 / 未登录拿到 null，保持全局视图。
        //
        // 不这么做的后果很具体：InventoryServiceImpl.getWarningInventories() 已改为
        // 按商户收敛，若这里只把预警数改成带 merchantId 而销售额仍取全量，
        // 一份报告里就会出现「你的 2 个预警」配「全平台 ¥X 销售额」的混合口径。
        Long merchantId = MerchantContext.currentMerchantId();
        Set<Long> ownedOrderIds = resolveOwnedOrderIds(merchantId);

        List<Order> orders = filterOwnedOrders(orderMapper.selectByDateRange(startDate, endDate), ownedOrderIds);
        
        BigDecimal totalSales = orders.stream()
                .filter(o -> o.getOrderStatus() == 3)
                .map(Order::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalProfit = calculateProfit(orders);
        
        int totalOrders = orders.size();
        int completedOrders = (int) orders.stream().filter(o -> o.getOrderStatus() == 3).count();
        
        Map<String, Integer> categorySales = new HashMap<>();
        for (Order order : orders) {
            if (order.getOrderStatus() == 3 && order.getItems() != null) {
                for (OrderItem item : order.getItems()) {
                    Product product = productMapper.selectById(item.getProductId());
                    if (product != null) {
                        String category = getCategoryName(product.getCategoryId());
                        categorySales.merge(category, item.getQuantity(), Integer::sum);
                    }
                }
            }
        }
        
        List<Map.Entry<String, Integer>> topCategories = categorySales.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toList());
        
        List<Product> topProducts = productMapper.selectTopProducts(10, merchantId);
        
        int warningInventoryCount = inventoryMapper.selectWarningInventories(merchantId).size();
        
        Map<String, Object> comparison = calculateComparison(startDate, endDate, orders, ownedOrderIds);
        
        String aiAdvice = generateAiAdvice(totalSales, totalProfit, topCategories, warningInventoryCount);
        
        return new BusinessReport(
                period,
                startDate,
                endDate,
                totalSales,
                totalProfit,
                totalOrders,
                completedOrders,
                categorySales,
                topCategories,
                topProducts,
                warningInventoryCount,
                comparison,
                aiAdvice,
                LocalDateTime.now()
        );
    }
    
    private BigDecimal calculateProfit(List<Order> orders) {
        BigDecimal totalProfit = BigDecimal.ZERO;
        
        for (Order order : orders) {
            if (order.getOrderStatus() == 3 && order.getItems() != null) {
                for (OrderItem item : order.getItems()) {
                    Product product = productMapper.selectById(item.getProductId());
                    if (product != null && product.getCost() != null) {
                        BigDecimal itemCost = product.getCost().multiply(BigDecimal.valueOf(item.getQuantity()));
                        BigDecimal itemRevenue = item.getAmount();
                        totalProfit = totalProfit.add(itemRevenue.subtract(itemCost));
                    }
                }
            }
        }
        
        return totalProfit.setScale(2, RoundingMode.HALF_UP);
    }
    
    private String getCategoryName(Long categoryId) {
        return "分类" + categoryId;
    }
    
    /**
     * 当前商家名下的订单 id 集合；管理员 / 未登录返回 null（表示「不限制」）。
     *
     * order 表没有 merchant_id 列，归属只能经 order_item → product 反查，
     * 与 OrderMapper.selectOrderIdsByMerchantId / OrderServiceImpl 的既有做法一致。
     */
    private Set<Long> resolveOwnedOrderIds(Long merchantId) {
        if (merchantId == null) {
            return null;
        }
        return new HashSet<>(orderMapper.selectOrderIdsByMerchantId(merchantId));
    }

    /** ownedOrderIds 为 null 表示不做限制（管理员全局视图）。 */
    private List<Order> filterOwnedOrders(List<Order> orders, Set<Long> ownedOrderIds) {
        if (ownedOrderIds == null) {
            return orders;
        }
        return orders.stream()
                .filter(o -> ownedOrderIds.contains(o.getId()))
                .collect(Collectors.toList());
    }

    private Map<String, Object> calculateComparison(LocalDate startDate, LocalDate endDate,
                                                   List<Order> currentOrders, Set<Long> ownedOrderIds) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        LocalDate prevStartDate = startDate.minusDays(days);
        LocalDate prevEndDate = startDate.minusDays(1);
        
        List<Order> previousOrders = filterOwnedOrders(
                orderMapper.selectByDateRange(prevStartDate, prevEndDate), ownedOrderIds);
        
        BigDecimal currentSales = currentOrders.stream()
                .filter(o -> o.getOrderStatus() == 3)
                .map(Order::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal previousSales = previousOrders.stream()
                .filter(o -> o.getOrderStatus() == 3)
                .map(Order::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        double salesGrowth = previousSales.compareTo(BigDecimal.ZERO) > 0
                ? currentSales.subtract(previousSales).divide(previousSales, 4, RoundingMode.HALF_UP).doubleValue() * 100
                : 0;
        
        Map<String, Object> comparison = new HashMap<>();
        comparison.put("previousPeriodSales", previousSales);
        comparison.put("currentPeriodSales", currentSales);
        comparison.put("salesGrowth", salesGrowth);
        comparison.put("currentOrderCount", currentOrders.size());
        comparison.put("previousOrderCount", previousOrders.size());
        
        return comparison;
    }
    
    private String generateAiAdvice(BigDecimal totalSales, BigDecimal totalProfit,
                                   List<Map.Entry<String, Integer>> topCategories,
                                   int warningInventoryCount) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("作为商业分析专家，请为以下经营数据生成专业的优化建议：\n\n");
        
        prompt.append(String.format("【销售总额】%.2f元\n", totalSales));
        prompt.append(String.format("【总利润】%.2f元\n", totalProfit));
        prompt.append(String.format("【利润率为】%.2f%%\n", 
                totalSales.compareTo(BigDecimal.ZERO) > 0 
                        ? totalProfit.divide(totalSales, 4, RoundingMode.HALF_UP).doubleValue() * 100 
                        : 0));
        
        if (!topCategories.isEmpty()) {
            prompt.append("【热销品类TOP5】\n");
            for (int i = 0; i < topCategories.size(); i++) {
                Map.Entry<String, Integer> entry = topCategories.get(i);
                prompt.append(String.format("%d. %s - 销量%d件\n", i + 1, entry.getKey(), entry.getValue()));
            }
        }
        
        prompt.append(String.format("【库存预警商品数】%d个\n", warningInventoryCount));
        
        prompt.append("\n请从以下方面提供优化建议：\n");
        prompt.append("1. 商品结构优化\n");
        prompt.append("2. 库存管理建议\n");
        prompt.append("3. 营销策略建议\n");
        prompt.append("4. 成本控制建议\n");
        
        return aiFacade.generateText(prompt.toString());
    }
    
    @Data
    public static class BusinessReport {
        private String period;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal totalSales;
        private BigDecimal totalProfit;
        private int totalOrders;
        private int completedOrders;
        private Map<String, Integer> categorySales;
        private List<Map.Entry<String, Integer>> topCategories;
        private List<Product> topProducts;
        private int warningInventoryCount;
        private Map<String, Object> comparison;
        private String aiAdvice;
        private LocalDateTime generateTime;
        
        public BusinessReport(String period, LocalDate startDate, LocalDate endDate,
                            BigDecimal totalSales, BigDecimal totalProfit,
                            int totalOrders, int completedOrders,
                            Map<String, Integer> categorySales,
                            List<Map.Entry<String, Integer>> topCategories,
                            List<Product> topProducts,
                            int warningInventoryCount,
                            Map<String, Object> comparison,
                            String aiAdvice,
                            LocalDateTime generateTime) {
            this.period = period;
            this.startDate = startDate;
            this.endDate = endDate;
            this.totalSales = totalSales;
            this.totalProfit = totalProfit;
            this.totalOrders = totalOrders;
            this.completedOrders = completedOrders;
            this.categorySales = categorySales;
            this.topCategories = topCategories;
            this.topProducts = topProducts;
            this.warningInventoryCount = warningInventoryCount;
            this.comparison = comparison;
            this.aiAdvice = aiAdvice;
            this.generateTime = generateTime;
        }
    }
}
