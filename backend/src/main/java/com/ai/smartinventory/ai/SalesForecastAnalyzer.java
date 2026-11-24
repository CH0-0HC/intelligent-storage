package com.ai.smartinventory.ai;

import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.mapper.OrderItemMapper;
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
public class SalesForecastAnalyzer {
    
    @Resource
    private OrderItemMapper orderItemMapper;
    
    @Resource
    private AiFacade aiFacade;
    
    public SalesForecastResult forecast(Long productId, int forecastDays) {
        List<OrderItem> historicalSales = orderItemMapper.selectByProductId(productId);
        
        Map<LocalDate, Integer> dailySales = calculateDailySales(historicalSales);
        
        List<ForecastPoint> forecast;
        
        if (dailySales.size() >= 14) {
            // 数据充足时使用指数平滑法
            forecast = exponentialSmoothingForecast(dailySales, forecastDays);
        } else if (dailySales.size() >= 7) {
            // 数据适中时使用加权移动平均
            forecast = weightedMovingAverageForecast(dailySales, forecastDays);
        } else {
            // 数据不足时使用线性回归
            forecast = linearRegressionForecast(dailySales, forecastDays);
        }
        
        SalesTrend trend = analyzeTrend(dailySales);
        String aiAdvice = getAiAdvice(productId, trend, forecast);
        
        return new SalesForecastResult(productId, historicalSales.size(), dailySales, forecast, trend, aiAdvice);
    }
    
    private Map<LocalDate, Integer> calculateDailySales(List<OrderItem> sales) {
        Map<LocalDate, Integer> dailySales = new TreeMap<>();
        
        for (OrderItem item : sales) {
            LocalDate date = item.getCreateTime().toLocalDate();
            dailySales.merge(date, item.getQuantity(), Integer::sum);
        }
        
        return dailySales;
    }
    
    /**
     * 加权移动平均预测
     * 越近的数据权重越大，使用线性递减权重
     */
    private List<ForecastPoint> weightedMovingAverageForecast(Map<LocalDate, Integer> dailySales, int days) {
        List<Integer> salesValues = new ArrayList<>(dailySales.values());
        int windowSize = Math.min(7, salesValues.size());
        List<Integer> recentSales = salesValues.subList(salesValues.size() - windowSize, salesValues.size());

        // 计算加权移动平均：最近一天权重为windowSize，最远一天权重为1
        double totalWeight = 0;
        double weightedSum = 0;
        for (int i = 0; i < recentSales.size(); i++) {
            double weight = i + 1;
            weightedSum += recentSales.get(i) * weight;
            totalWeight += weight;
        }
        double wma = totalWeight > 0 ? weightedSum / totalWeight : 0;

        List<ForecastPoint> forecast = new ArrayList<>();
        LocalDate startDate = LocalDate.now().plusDays(1);

        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            double seasonalFactor = calculateSeasonalFactor(date);
            int predicted = (int) Math.round(wma * seasonalFactor);

            ForecastPoint point = new ForecastPoint();
            point.setDate(date);
            point.setPredictedQuantity(predicted);
            point.setConfidence(calculateConfidence(i, salesValues.size()));
            forecast.add(point);
        }

        return forecast;
    }

    /**
     * 指数平滑法预测 (Holt-Winters简化版)
     * 使用单一指数平滑，对近期数据赋予更高权重
     * alpha = 0.3 平滑系数
     */
    private List<ForecastPoint> exponentialSmoothingForecast(Map<LocalDate, Integer> dailySales, int days) {
        List<Integer> salesValues = new ArrayList<>(dailySales.values());
        double alpha = 0.3; // 平滑系数

        // 计算指数平滑值
        double smoothed = salesValues.get(0);
        for (int i = 1; i < salesValues.size(); i++) {
            smoothed = alpha * salesValues.get(i) + (1 - alpha) * smoothed;
        }

        // 计算趋势(最近7天 vs 前7天的变化率)
        double trend = calculateTrendRate(salesValues);

        List<ForecastPoint> forecast = new ArrayList<>();
        LocalDate startDate = LocalDate.now().plusDays(1);

        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            // 预测值 = 平滑值 * (1 + 趋势)^(i+1) * 季节因子
            double trendFactor = Math.pow(1 + trend, i + 1);
            double seasonalFactor = calculateSeasonalFactor(date);
            int predicted = (int) Math.round(smoothed * trendFactor * seasonalFactor);

            ForecastPoint point = new ForecastPoint();
            point.setDate(date);
            point.setPredictedQuantity(Math.max(0, predicted));
            point.setConfidence(calculateConfidence(i, salesValues.size()));
            forecast.add(point);
        }

        return forecast;
    }

    /**
     * 计算趋势率
     * 比较最近一半数据与前半数据的变化
     */
    private double calculateTrendRate(List<Integer> salesValues) {
        if (salesValues.size() < 4) return 0;

        int mid = salesValues.size() / 2;
        double recentAvg = salesValues.subList(mid, salesValues.size())
                .stream().mapToInt(Integer::intValue).average().orElse(0);
        double oldAvg = salesValues.subList(0, mid)
                .stream().mapToInt(Integer::intValue).average().orElse(0);

        if (oldAvg == 0) return 0;
        return (recentAvg - oldAvg) / oldAvg / mid; // 日均变化率
    }

    private List<ForecastPoint> simpleMovingAverageForecast(Map<LocalDate, Integer> dailySales, int days) {
        List<Integer> recentSales = new ArrayList<>(dailySales.values());
        if (recentSales.size() < 7) {
            recentSales = fillMissingDays(dailySales, 7);
        }
        
        int windowSize = Math.min(7, recentSales.size());
        List<Integer> lastWeek = recentSales.subList(recentSales.size() - windowSize, recentSales.size());
        double avg = lastWeek.stream().mapToInt(Integer::intValue).average().orElse(0);
        
        List<ForecastPoint> forecast = new ArrayList<>();
        LocalDate startDate = LocalDate.now().plusDays(1);
        
        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            double seasonalFactor = calculateSeasonalFactor(date);
            int predicted = (int) Math.round(avg * seasonalFactor);
            
            ForecastPoint point = new ForecastPoint();
            point.setDate(date);
            point.setPredictedQuantity(predicted);
            point.setConfidence(calculateConfidence(i, recentSales.size()));
            forecast.add(point);
        }
        
        return forecast;
    }
    
    private List<ForecastPoint> linearRegressionForecast(Map<LocalDate, Integer> dailySales, int days) {
        List<LocalDate> dates = new ArrayList<>(dailySales.keySet());
        List<Integer> values = new ArrayList<>(dailySales.values());
        
        int n = dates.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = values.get(i);
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;
        
        List<ForecastPoint> forecast = new ArrayList<>();
        LocalDate startDate = LocalDate.now().plusDays(1);
        
        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            double x = n + i;
            double predicted = slope * x + intercept;
            predicted = Math.max(0, predicted);
            
            ForecastPoint point = new ForecastPoint();
            point.setDate(date);
            point.setPredictedQuantity((int) Math.round(predicted));
            point.setConfidence(calculateConfidence(i, n));
            forecast.add(point);
        }
        
        return forecast;
    }
    
    private List<Integer> fillMissingDays(Map<LocalDate, Integer> dailySales, int targetDays) {
        List<Integer> result = new ArrayList<>();
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(targetDays - 1);
        
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            result.add(dailySales.getOrDefault(date, 0));
        }
        
        return result;
    }
    
    private double calculateSeasonalFactor(LocalDate date) {
        int dayOfWeek = date.getDayOfWeek().getValue();
        if (dayOfWeek == 6 || dayOfWeek == 7) {
            return 1.3;
        }
        return 1.0;
    }
    
    private double calculateConfidence(int forecastIndex, int historicalDays) {
        double baseConfidence = 0.9;
        double decay = 0.02 * forecastIndex;
        double historicalBonus = Math.min(0.05, historicalDays * 0.001);
        return Math.max(0.5, baseConfidence - decay + historicalBonus);
    }
    
    private SalesTrend analyzeTrend(Map<LocalDate, Integer> dailySales) {
        List<Integer> sales = new ArrayList<>(dailySales.values());
        if (sales.size() < 2) {
            return new SalesTrend("平稳", 0);
        }
        
        int recent = sales.subList(Math.max(0, sales.size() - 7), sales.size())
                          .stream().mapToInt(Integer::intValue).sum();
        int previous = sales.subList(Math.max(0, sales.size() - 14), Math.max(0, sales.size() - 7))
                             .stream().mapToInt(Integer::intValue).sum();
        
        double changeRate = previous > 0 ? (double) (recent - previous) / previous : 0;
        
        String trendType;
        if (changeRate > 0.2) {
            trendType = "上升";
        } else if (changeRate < -0.2) {
            trendType = "下降";
        } else {
            trendType = "平稳";
        }
        
        return new SalesTrend(trendType, changeRate * 100);
    }
    
    private String getAiAdvice(Long productId, SalesTrend trend, List<ForecastPoint> forecast) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("作为销售分析专家，请根据以下数据为商品销量预测提供建议：\n\n");
        
        prompt.append(String.format("【销售趋势】%s，增长率: %.1f%%\n", trend.getTrendType(), trend.getChangeRate()));
        
        if (!forecast.isEmpty()) {
            int totalForecast = forecast.stream().mapToInt(ForecastPoint::getPredictedQuantity).sum();
            prompt.append(String.format("【未来%d天预测销量】%d件\n", forecast.size(), totalForecast));
        }
        
        prompt.append("\n请给出进货建议、促销策略和库存管理建议。");
        
        return aiFacade.generateText(prompt.toString());
    }
    
    @Data
    public static class SalesForecastResult {
        private Long productId;
        private int historicalDataPoints;
        private Map<LocalDate, Integer> historicalSales;
        private List<ForecastPoint> forecast;
        private SalesTrend trend;
        private String aiAdvice;
        private LocalDateTime generateTime;
        
        public SalesForecastResult(Long productId, int historicalDataPoints,
                                 Map<LocalDate, Integer> historicalSales,
                                 List<ForecastPoint> forecast,
                                 SalesTrend trend, String aiAdvice) {
            this.productId = productId;
            this.historicalDataPoints = historicalDataPoints;
            this.historicalSales = historicalSales;
            this.forecast = forecast;
            this.trend = trend;
            this.aiAdvice = aiAdvice;
            this.generateTime = LocalDateTime.now();
        }
    }
    
    @Data
    public static class ForecastPoint {
        private LocalDate date;
        private int predictedQuantity;
        private double confidence;
    }
    
    @Data
    public static class SalesTrend {
        private String trendType;
        private double changeRate;
        
        public SalesTrend(String trendType, double changeRate) {
            this.trendType = trendType;
            this.changeRate = changeRate;
        }
    }
}
