package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.ai.smartinventory.ai.AiFacade;
import com.ai.smartinventory.ai.BusinessReportGenerator;
import com.ai.smartinventory.ai.CollaborativeFilteringRecommender;
import com.ai.smartinventory.ai.InventoryWarningAnalyzer;
import com.ai.smartinventory.ai.SalesForecastAnalyzer;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.ProductVisibility;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.dto.AiConfigDTO;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.mapper.OrderItemMapper;
import com.ai.smartinventory.mapper.OrderMapper;
import com.ai.smartinventory.mapper.ProductCategoryMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.service.AiConfigService;
import com.ai.smartinventory.service.InventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {
    
    @Resource
    private AiFacade aiFacade;

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private CollaborativeFilteringRecommender recommender;
    
    @Resource
    private InventoryWarningAnalyzer inventoryAnalyzer;
    
    @Resource
    private SalesForecastAnalyzer forecastAnalyzer;
    
    @Resource
    private BusinessReportGenerator reportGenerator;
    
    @Resource
    private OrderMapper orderMapper;
    
    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private ProductMapper productMapper;

    @Resource
    private ProductCategoryMapper productCategoryMapper;
    
    @Resource
    private InventoryService inventoryService;
    
    @RateLimit(permitsPerMinute = 10, permitsPerHour = 100)
    // 【M-5 修复】AI 对话是商家经营助手（前端 AIAssistant 仅对 merchant 展示），
    // 此前无角色注解 ⇒ 任意登录用户（含买家）都能调用、可被刷 AI 成本。现收敛到 admin/merchant。
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.trim().isEmpty()) {
            return Result.error("请输入消息");
        }
        
        log.info("AI聊天请求: {}", message);
        String response = aiFacade.generateText(message);
        log.info("AI聊天响应: {}", response);
        
        return Result.success(response);
    }
    
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        // 【修复】原实现只报本地连通性与模型列表，看不出「到底哪条链路在生效」，
        // 也无法区分「服务没起」与「服务起了但模型没拉下来」。
        // 现统一由 AiFacade 汇总，包含 effective provider、本地模型是否就绪，
        // 以及前端直接消费的 connected 布尔值（此前响应里没有该字段，
        // 前端读取 status.connected 恒为 undefined，页面永远显示「AI 未连接」）。
        //
        // 说明：本接口在 JwtAuthenticationFilter 中属于公开路径，
        // 未登录时按「内置默认配置」探测；已登录商家则按其自有配置探测，
        // 因此同一接口对不同商家可返回不同结果。
        return Result.success(aiFacade.status());
    }

    // ==================== AI 连接配置（商家自助） ====================
    // 【新增原因】此前 Ollama 地址与模型只能改 application.yml 再重启，
    // 商家在界面上没有任何配置入口。以下五个接口构成完整的自助配置能力：
    // 读当前配置 / 保存 / 恢复默认 / 测试连接 / 拉取模型列表。
    // 保存后**立即生效**（下一次 AI 调用即读取新值），无需重启后端。

    /** 读取当前生效的 AI 配置（含内置默认值、来源、连通性探测结果） */
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/config")
    public Result<Map<String, Object>> getAiConfig() {
        aiConfigService.assertCanManageConfig();
        return Result.success(aiConfigService.describeCurrent());
    }

    /** 保存当前账号的 AI 配置（商户配置只影响自己，管理员配置为全局默认） */
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 【P1-1 限流补全】AI 配置写操作原先无限流。
    @RateLimit(key = "ai:config:save", permitsPerMinute = 20, permitsPerHour = 100)
    @PutMapping("/config")
    public Result<Map<String, Object>> saveAiConfig(@RequestBody AiConfigDTO dto) {
        return Result.success("AI 配置已保存并即时生效", aiConfigService.saveCurrent(dto));
    }

    /** 清除当前账号的配置，回落到全局默认 / 内置默认 */
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @RateLimit(key = "ai:config:reset", permitsPerMinute = 10, permitsPerHour = 50)
    @PostMapping("/config/reset")
    public Result<Map<String, Object>> resetAiConfig() {
        return Result.success("已恢复为默认 AI 配置", aiConfigService.resetCurrent());
    }

    /** 用尚未保存的参数试连一次，避免「保存完才发现地址写错」 */
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 试连会发起对外 HTTP 请求，限制频率防被当作内网探测跳板。
    @RateLimit(key = "ai:config:test", permitsPerMinute = 10, permitsPerHour = 60)
    @PostMapping("/config/test")
    public Result<Map<String, Object>> testAiConfig(@RequestBody(required = false) Map<String, String> body) {
        String baseUrl = body == null ? null : body.get("baseUrl");
        String model = body == null ? null : body.get("model");
        return Result.success(aiConfigService.testConnection(baseUrl, model));
    }

    /** 拉取指定地址下已安装的模型列表（供设置弹窗的模型下拉框） */
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/config/models")
    public Result<List<String>> listAiModels(@RequestParam(required = false) String baseUrl) {
        return Result.success(aiConfigService.listModels(baseUrl));
    }
    
    // 【M-5 修复】原实现接受任意 userId 入参，可据他人行为生成推荐（弱隐私推断）；
    // 现忽略入参，强制取当前登录用户。
    @SaCheckLogin
    @GetMapping("/recommend")
    public Result<List<Product>> getPersonalizedRecommendations(
            @RequestParam(defaultValue = "10") Integer limit) {
        // 【异常出口收口】不再 catch 后回吐 e.getMessage()
        // 【安全修复·S-2】推荐结果可能含其他商家的商品，必须屏蔽成本价。
        Long userId = StpUtil.getLoginIdAsLong();
        List<Product> recommendations = ProductVisibility.maskCost(recommender.recommend(userId, limit));
        return Result.success(recommendations);
    }
    
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/inventory/warning")
    public Result<InventoryWarningAnalyzer.InventoryWarningResult> getInventoryWarning() {
        // 【异常出口收口】AI 不可用由 AiFacade 内部降级为可读文案（不会抛异常），
        // 故此处的 catch 只会兜住意外 bug —— 直接去掉，交给 GlobalExceptionHandler 脱敏。
        InventoryWarningAnalyzer.InventoryWarningResult result = inventoryAnalyzer.analyze();
        return Result.success(result);
    }
    
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/sales/forecast")
    public Result<SalesForecastAnalyzer.SalesForecastResult> forecastSales(
            // 【R19】改为声明式必填：原先 required=false + 手工判空返回 Result.error，
            // 语义上缺参数却给出 HTTP 200，与全局异常口径不一致。
            // 去掉手工判空后由框架抛 MissingServletRequestParameterException，
            // 交给 GlobalExceptionHandler 兜成 HTTP 400 + code 400（脱敏、统一）。
            @RequestParam Long productId,
            @RequestParam(defaultValue = "7") Integer days) {
        // 【多租户边界（2026-09-22 补全）】销量预测按 productId 取 order_item 历史，
        // 不校验归属时商家可传入他人商品 id，读出对方的销量曲线与成交节奏。
        // 约定与 InventoryController.assertProductAccessible 一致：对**不存在**的商品
        // 也一律 403，不区分「此 id 不存在」与「此 id 属于别人」，避免被用来枚举商品 id。
        Product target = productMapper.selectById(productId);
        MerchantContext.assertCanAccessMerchantData(target == null ? null : target.getMerchantId());
        SalesForecastAnalyzer.SalesForecastResult result = forecastAnalyzer.forecast(productId, days);
        return Result.success(result);
    }
    
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/report")
    public Result<BusinessReportGenerator.BusinessReport> generateReport(
            @RequestParam(defaultValue = "month") String period) {
        BusinessReportGenerator.BusinessReport report = reportGenerator.generateReport(period);
        return Result.success(report);
    }
    
    /**
     * 仪表盘综合统计 - 聚合日/周/月维度的销售、订单、库存数据
     */
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> getDashboardStats() {
        try {
            LocalDate today = LocalDate.now();
            LocalDate weekStart = today.minusDays(7);
            LocalDate monthStart = today.minusDays(30);

            // 【多商户隔离修复】原实现直接 selectByDateRange(全量) + selectCount(null)，
            // 商家调用 /ai/dashboard 会拿到**全平台所有商家**的销售/商品聚合数据，
            // 属于经营数据泄露（也是首页商家工作台的取数接口，会直接暴露在 UI 上）。
            // 现按会话中的 merchantId 收敛数据范围：
            //   - merchant：订单只保留「含本商家商品」的订单；商品数只数自己的；
            //   - admin / user：保持全局视图（currentMerchantId() 返回 null）。
            Long merchantId = MerchantContext.currentMerchantId();

            List<Order> allOrders = loadOwnedOrders(monthStart, today, merchantId);
            // 售卖实况聚合窗口：最近 6 个月（月趋势 / 销量TOP / 品类占比）
            List<Order> orders6m = loadOwnedOrders(today.minusMonths(6), today, merchantId);
            
            // 今日统计
            List<Order> todayOrders = allOrders.stream()
                    .filter(o -> o.getCreateTime().toLocalDate().equals(today))
                    .collect(Collectors.toList());
            BigDecimal todaySales = todayOrders.stream()
                    .filter(AiController::isSalesOrder)
                    .map(o -> o.getPayAmount() != null ? o.getPayAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 本周统计
            List<Order> weekOrders = allOrders.stream()
                    .filter(o -> !o.getCreateTime().toLocalDate().isBefore(weekStart))
                    .collect(Collectors.toList());
            BigDecimal weekSales = weekOrders.stream()
                    .filter(AiController::isSalesOrder)
                    .map(o -> o.getPayAmount() != null ? o.getPayAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 月度统计
            BigDecimal monthSales = allOrders.stream()
                    .filter(AiController::isSalesOrder)
                    .map(o -> o.getPayAmount() != null ? o.getPayAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 日销售趋势（最近30天；orders 为当日有效销售订单量，供前端趋势百分比推导）
            Map<LocalDate, BigDecimal> dailyTrend = new TreeMap<>();
            Map<LocalDate, Long> dailyOrderCounts = new TreeMap<>();
            for (int i = 29; i >= 0; i--) {
                LocalDate date = today.minusDays(i);
                dailyTrend.putIfAbsent(date, BigDecimal.ZERO);
                dailyOrderCounts.putIfAbsent(date, 0L);
            }
            for (Order order : allOrders) {
                if (isSalesOrder(order)) {
                    LocalDate date = order.getCreateTime().toLocalDate();
                    dailyTrend.merge(date, order.getPayAmount() != null ? order.getPayAmount() : BigDecimal.ZERO, BigDecimal::add);
                    dailyOrderCounts.merge(date, 1L, Long::sum);
                }
            }
            
            // 商品总数和预警数
            // 【多商户隔离】商家只统计自己名下的商品与预警，管理员看全平台
            long totalProducts;
            long warningCount;
            if (merchantId == null) {
                totalProducts = productMapper.selectCount(null);
                warningCount = inventoryService.getWarningInventories().size();
            } else {
                totalProducts = productMapper.selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                                .eq(Product::getMerchantId, merchantId));
                Set<Long> ownedProductIds = productMapper.selectList(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                                        .select(Product::getId)
                                        .eq(Product::getMerchantId, merchantId))
                        .stream().map(Product::getId).collect(Collectors.toSet());
                warningCount = inventoryService.getWarningInventories().stream()
                        .filter(inv -> ownedProductIds.contains(inv.getProductId()))
                        .count();
            }
            
            // ====== 售卖实况聚合（2026-09-23 · 数据可视化接真实订单） ======
            // 口径：有效销售 = 已付款且未取消（payStatus=1 且 orderStatus != 4）。
            // 全部由真实订单 / order_item 聚合得出 —— 不再使用 Math.random()、
            // 写死的示例数组，也不再依赖从不更新的 product.sales 字段。
            List<Order> salesOrders = orders6m.stream()
                    .filter(AiController::isSalesOrder)
                    .collect(Collectors.toList());

            // 周趋势：本周周一 ~ 周日，按天聚合销售额与订单量（供商家工作台周报表使用）
            LocalDate weekMonday = today.with(DayOfWeek.MONDAY);
            List<Map<String, Object>> weeklyTrend = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                final LocalDate day = weekMonday.plusDays(i);
                List<Order> dayOrders = salesOrders.stream()
                        .filter(o -> o.getCreateTime().toLocalDate().equals(day))
                        .collect(Collectors.toList());
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date", day.toString());
                point.put("label", String.format("%02d-%02d", day.getMonthValue(), day.getDayOfMonth()));
                point.put("amount", sumSaleAmount(dayOrders));
                point.put("orders", dayOrders.size());
                weeklyTrend.add(point);
            }

            // 月趋势：最近 6 个月（含本月），按月聚合销售额与订单量（供数据可视化销售趋势图）
            List<Map<String, Object>> monthlyTrend = new ArrayList<>();
            YearMonth currentMonth = YearMonth.from(today);
            for (int i = 5; i >= 0; i--) {
                final YearMonth bucket = currentMonth.minusMonths(i);
                List<Order> monthOrders = salesOrders.stream()
                        .filter(o -> YearMonth.from(o.getCreateTime().toLocalDate()).equals(bucket))
                        .collect(Collectors.toList());
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("month", bucket.getMonthValue() + "月");
                point.put("amount", sumSaleAmount(monthOrders));
                point.put("orders", monthOrders.size());
                monthlyTrend.add(point);
            }

            // 销量 TOP10（按 order_item 真实售出件数）与品类销售占比（按销售额）
            List<Map<String, Object>> productRanking = new ArrayList<>();
            List<Map<String, Object>> categoryShare = new ArrayList<>();
            Set<Long> salesOrderIds = salesOrders.stream().map(Order::getId).collect(Collectors.toSet());
            if (!salesOrderIds.isEmpty()) {
                List<OrderItem> salesItems = orderItemMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderItem>()
                                .in(OrderItem::getOrderId, salesOrderIds));

                // 商品维度聚合：销量（件）与销售额
                Map<Long, List<OrderItem>> itemsByProduct = salesItems.stream()
                        .filter(it -> it.getProductId() != null)
                        .collect(Collectors.groupingBy(OrderItem::getProductId));
                List<Map<String, Object>> ranking = new ArrayList<>();
                for (Map.Entry<Long, List<OrderItem>> e : itemsByProduct.entrySet()) {
                    int qty = e.getValue().stream()
                            .mapToInt(it -> it.getQuantity() != null ? it.getQuantity() : 0).sum();
                    BigDecimal amt = e.getValue().stream()
                            .map(this::itemAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("productId", e.getKey());
                    row.put("name", e.getValue().get(0).getProductName());
                    row.put("quantity", qty);
                    row.put("amount", amt.setScale(2, RoundingMode.HALF_UP));
                    ranking.add(row);
                }
                ranking.sort((a, b) -> Integer.compare((Integer) b.get("quantity"), (Integer) a.get("quantity")));
                productRanking = ranking.size() > 10 ? ranking.subList(0, 10) : ranking;

                // 品类维度聚合：销售额占比（product.categoryName 是 exist=false 字段，
                // 普通查询不填充，故经 product → product_category 解析真实分类名）
                Map<Long, String> categoryNames = new HashMap<>();
                productCategoryMapper.selectList(null)
                        .forEach(c -> categoryNames.put(c.getId(), c.getName()));
                Map<Long, Long> productCategory = new HashMap<>();
                productMapper.selectBatchIds(itemsByProduct.keySet())
                        .forEach(p -> productCategory.put(p.getId(), p.getCategoryId()));
                Map<String, BigDecimal> amountByCategory = new LinkedHashMap<>();
                for (Map.Entry<Long, List<OrderItem>> e : itemsByProduct.entrySet()) {
                    Long categoryId = productCategory.get(e.getKey());
                    String name = categoryId != null
                            ? categoryNames.getOrDefault(categoryId, "未分类")
                            : "未分类";
                    BigDecimal amt = e.getValue().stream()
                            .map(this::itemAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    amountByCategory.merge(name, amt, BigDecimal::add);
                }
                amountByCategory.entrySet().stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .forEach(en -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", en.getKey());
                            row.put("amount", en.getValue().setScale(2, RoundingMode.HALF_UP));
                            categoryShare.add(row);
                        });
            }

            // 订单状态分布
            Map<Integer, Long> orderStatusDist = allOrders.stream()
                    .collect(Collectors.groupingBy(Order::getOrderStatus, Collectors.counting()));
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("todaySales", todaySales.setScale(2, RoundingMode.HALF_UP));
            result.put("todayOrders", todayOrders.size());
            result.put("weekSales", weekSales.setScale(2, RoundingMode.HALF_UP));
            result.put("weekOrders", weekOrders.size());
            result.put("monthSales", monthSales.setScale(2, RoundingMode.HALF_UP));
            result.put("monthOrders", allOrders.size());
            result.put("totalProducts", totalProducts);
            result.put("warningCount", warningCount);
            result.put("orderStatusDist", orderStatusDist);
            result.put("dailyTrend", dailyTrend.entrySet().stream()
                    .map(e -> Map.of("date", e.getKey().toString(), "amount", e.getValue(),
                            "orders", dailyOrderCounts.getOrDefault(e.getKey(), 0L)))
                    .collect(Collectors.toList()));
            // 售卖实况（2026-09-23 · 数据可视化接真实订单）
            result.put("weeklyTrend", weeklyTrend);
            result.put("monthlyTrend", monthlyTrend);
            result.put("productRanking", productRanking);
            result.put("categoryShare", categoryShare);
            result.put("generateTime", LocalDateTime.now().toString());
            
            return Result.success(result);
        } catch (Exception e) {
            // 【异常出口收口】此处保留 catch 以便留下「仪表盘统计」这一上下文日志，
            // 但**不再回吐 e.getMessage()**（聚合查询的异常消息可能带 SQL 片段）。
            // 说明：本方法体量大（百余行聚合），整体去掉 try 需要全文重排缩进，
            // 收益仅为样式统一，故改为「保留结构 + 脱敏」。
            log.error("仪表盘统计失败", e);
            return Result.error(500, "统计失败，请稍后重试");
        }
    }
    
    /**
     * 获取所有商品列表（供预测功能选择商品）
     */
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/products")
    public Result<List<Product>> getProductListForAI() {
        // 【安全修复·M-2】原实现 productMapper.selectList(null) 对任意商家返回**全平台**商品
        // （含其他商家的成本价 cost），违反多租户隔离。现按当前商家收敛：
        // 商家只看到自己名下商品；管理员仍为全局视图。
        Long merchantId = MerchantContext.currentMerchantId();
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (merchantId != null) {
            wrapper.eq(Product::getMerchantId, merchantId);
        }
        // 【异常出口收口】纯库查询，异常交给 GlobalExceptionHandler 统一脱敏
        List<Product> products = productMapper.selectList(wrapper);
        return Result.success(products);
    }

    // ==================== 售卖实况聚合辅助（2026-09-23） ====================

    /**
     * 「实际售卖」口径：已付款，或已发货/已完成（演示环境的虚拟支付可能被跳过，
     * 走到发货/签收的订单就是真实成交）；已取消(4)一律不计。
     *
     * 原实现仅按 payStatus==1 统计 —— 未走虚拟支付但已发货签收的真实成交单
     * 不会进入任何销售数字，是「可视化数据与真实售卖脱节」的另一半原因。
     */
    private static boolean isSalesOrder(Order o) {
        if (o.getOrderStatus() != null && o.getOrderStatus() == 4) {
            return false;
        }
        boolean paid = o.getPayStatus() != null && o.getPayStatus() == 1;
        boolean fulfilled = o.getOrderStatus() != null
                && (o.getOrderStatus() == 2 || o.getOrderStatus() == 3);
        return paid || fulfilled;
    }

    /** 按日期窗口取订单；商家按 order_item → product.merchant_id 反查收敛到本商家订单。 */
    private List<Order> loadOwnedOrders(LocalDate from, LocalDate to, Long merchantId) {
        List<Order> orders = orderMapper.selectByDateRange(from, to);
        if (merchantId == null) {
            return orders;
        }
        Set<Long> ownedOrderIds = new HashSet<>(orderMapper.selectOrderIdsByMerchantId(merchantId));
        return orders.stream()
                .filter(o -> ownedOrderIds.contains(o.getId()))
                .collect(Collectors.toList());
    }

    /** 订单实收金额（payAmount 为空按 0 计）。 */
    private BigDecimal saleAmount(Order o) {
        return o.getPayAmount() != null ? o.getPayAmount() : BigDecimal.ZERO;
    }

    /** 订单销售额合计（保留 2 位小数）。 */
    private BigDecimal sumSaleAmount(List<Order> orders) {
        return orders.stream()
                .map(this::saleAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 订单明细金额（缺 amount 时按 price × quantity 计）。 */
    private BigDecimal itemAmount(OrderItem it) {
        if (it.getAmount() != null) {
            return it.getAmount();
        }
        if (it.getPrice() == null || it.getQuantity() == null) {
            return BigDecimal.ZERO;
        }
        return it.getPrice().multiply(BigDecimal.valueOf(it.getQuantity()));
    }
}
