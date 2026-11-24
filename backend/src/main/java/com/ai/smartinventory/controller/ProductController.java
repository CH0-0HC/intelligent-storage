package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.MoneyBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.ProductVisibility;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.common.UserBehaviorRecorder;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/product")
public class ProductController {
    
    @Resource
    private ProductService productService;

    /** R18：用户行为埋点（浏览详情 → view） */
    @Resource
    private UserBehaviorRecorder userBehaviorRecorder;
    
    @GetMapping("/list")
    public Result<PageResult<Product>> getProductList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "createTime") String sort,
            @RequestParam(defaultValue = "desc") String order) {
        
        PageQueryDTO params = new PageQueryDTO();
        params.setPage(page);
        params.setSize(size);
        params.setKeyword(keyword);
        params.setCategoryId(categoryId);
        params.setStatus(status);
        params.setSort(sort);
        params.setOrder(order);
        
        return Result.success(productService.getProductPage(params));
    }
    
    @GetMapping("/{id}")
    public Result<Product> getProductById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null || product.getDeleted() == 1) {
            return Result.error("商品不存在");
        }
        // 【R18】埋点：登录用户浏览商品记 view 行为。
        // 本接口在**匿名白名单**内（JwtAuthenticationFilter 精确放行「/api/product/ + 纯数字 id」），
        // 所以 record() 内部先判 userId 是否为 null —— 匿名访问静默跳过，绝不抛异常。
        userBehaviorRecorder.record(UserBehaviorRecorder.TYPE_VIEW, id);
        // 【安全修复·S-2】成本价仅管理员与所属商家可见；买家/游客/其他商家返回 null。
        Product visible = ProductVisibility.maskCost(product);
        // 【2026-10-10】回填分类名（exist=false 展示字段），否则详情页拿不到 categoryName。
        productService.fillCategoryNames(List.of(visible));
        return Result.success(visible);
    }
    
    // 【已移除的缓存注解，勿再加回】
    // 这三个只读接口原先挂着 @Cacheable(hotProducts / recommendProducts /
    // categoryProducts)，增删改侧配了对应的 @CacheEvict。但全项目没有
    // @EnableCaching，这些注解**从未生效**（每次请求照旧查库，日志可见）。
    // 更麻烦的是它构成一颗雷：spring-boot-starter-data-redis 在依赖里，
    // 一旦有人补上 @EnableCaching，Spring Boot 会自动装配 RedisCacheManager，
    // 而 Redis（6379）本机并未运行 —— 这三个接口会立刻全部 500。
    // 收益（3 个只读接口）远小于风险，故直接删掉注解与 spring.cache.redis.*
    // 配置。真要缓存，请先确保 Redis 可用，再显式引入并做本地兜底。
    @GetMapping("/hot")
    public Result<List<Product>> getHotProducts(@RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(productService.getHotProducts(limit));
    }
    
    @GetMapping("/recommend")
    public Result<List<Product>> getRecommendProducts(@RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(productService.getRecommendProducts(limit));
    }
    
    @GetMapping("/category/{categoryId}")
    public Result<List<Product>> getProductsByCategory(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "20") Integer limit) {
        return Result.success(productService.getProductsByCategory(categoryId, limit));
    }
    
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 【P1-1 限流补全】商品写接口，限流防批量刷。
    @RateLimit(key = "product:add", permitsPerMinute = 30, permitsPerHour = 300)
    @PostMapping
    public Result<String> addProduct(@RequestBody Product product) {
        // 【参数校验】name / category_id / price 在 product 表都是 NOT NULL，
        // 此前一个都不校验：新建商品漏填任一字段都会一路走到 SQL 层报错，
        // 最终以「系统繁忙，请稍后重试」呈现，前端无从得知到底缺了什么。
        if (product.getName() == null || product.getName().isBlank()) {
            return Result.error(400, "请填写商品名称");
        }
        if (product.getCategoryId() == null) {
            return Result.error(400, "请选择商品分类");
        }
        if (product.getPrice() == null) {
            return Result.error(400, "请填写商品价格");
        }
        // 【P1-2】价格/成本此前只判 null，负数与超大值都能落库（decimal(10,2) 会被截断）。
        // price 必填（上面已判空，故这里 null 分支不会触发）；cost / stock 库中可空，
        // 用 IfPresent 变体：不填放行，填了才校验范围。
        String priceErr = MoneyBounds.validateNonNegative(product.getPrice(), "商品价格");
        if (priceErr != null) {
            return Result.error(400, priceErr);
        }
        String costErr = MoneyBounds.validateNonNegativeIfPresent(product.getCost(), "成本价");
        if (costErr != null) {
            return Result.error(400, costErr);
        }
        String stockErr = MoneyBounds.validateNonNegative(product.getStock(), "库存");
        if (stockErr != null) {
            return Result.error(400, stockErr);
        }
        // 【2026-09-28】商品级退货退款规则校验：
        // 商家在「上架前」设置该商品支持的退货期限（7/15/30 或自定义，0=不支持退货）。
        // 这是用户侧售后时效的唯一来源，写错会直接影响买家权益，故在入口拦一次。
        String windowErr = validateReturnRule(product);
        if (windowErr != null) {
            return Result.error(400, windowErr);
        }

        // 【多商户隔离】商家新建商品时强制把 merchantId 绑定为「当前登录商家」，
        // 忽略请求体里传入的 merchantId，防止商家把自己商品挂到别人名下，
        // 也防止伪造 merchantId 造成数据错乱。管理员不覆盖（可代任意商户建品）。
        Long currentMerchantId = MerchantContext.currentMerchantId();
        if (currentMerchantId != null) {
            product.setMerchantId(currentMerchantId);
        }
        return productService.save(product) ? Result.success("添加成功") : Result.error(500, "添加失败");
    }

    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 【P1-1 限流补全】商品写接口，限流防批量刷。
    @RateLimit(key = "product:update", permitsPerMinute = 30, permitsPerHour = 300)
    @PutMapping
    public Result<String> updateProduct(@RequestBody Product product) {
        // 【多商户隔离】先校验商品归属，再执行更新。
        // 原实现直接 updateById，商家只要构造一个带别人商品 ID 的请求体
        // 就能篡改他人商品的价格、库存、上下架状态，属严重越权。
        Product existing = productService.getById(product.getId());
        if (existing == null) {
            return Result.error("商品不存在");
        }
        MerchantContext.assertCanAccessMerchantData(existing.getMerchantId());
        // 商家不允许篡改归属关系
        if (MerchantContext.isMerchant()) {
            product.setMerchantId(existing.getMerchantId());
        }
        // 【P1-2】更新同样要校验金额范围：此前只校验归属，价格可被改成负数/超大值。
        // 更新场景三个字段都可能为 null（表示「不修改该字段」），
        // 故统一用 IfPresent 变体：传 null 放行，传了才校验范围。
        String priceErr = MoneyBounds.validateNonNegativeIfPresent(product.getPrice(), "商品价格");
        if (priceErr != null) {
            return Result.error(400, priceErr);
        }
        String costErr = MoneyBounds.validateNonNegativeIfPresent(product.getCost(), "成本价");
        if (costErr != null) {
            return Result.error(400, costErr);
        }
        String stockErr = MoneyBounds.validateNonNegative(product.getStock(), "库存");
        if (stockErr != null) {
            return Result.error(400, stockErr);
        }
        // 【2026-09-28】退货运则同样校验；null 表示「不修改」，放行（与上面三个字段同口径）
        String windowErr = validateReturnRule(product);
        if (windowErr != null) {
            return Result.error(400, windowErr);
        }
        return productService.updateById(product) ? Result.success("更新成功") : Result.error(500, "更新失败");
    }

    /**
     * 校验商品级退货退款规则入参。
     *
     * <p>口径：{@code returnWindowDays} 允许 {@code null}（= 不修改 / 沿用默认 15 天）、
     * {@code 0}（= 该商品不支持退货退款）、或 {@code 1..365}；
     * {@code returnNoReason} 只接受 0 / 1 / null。
     *
     * @return 合法返回 {@code null}；否则返回可直接回吐前端的错误文案
     */
    private String validateReturnRule(Product product) {
        Integer days = product.getReturnWindowDays();
        if (days != null && !com.ai.smartinventory.common.AfterSaleRule.isValidReturnWindowDays(days)) {
            return "退货退款期限需为 0（不支持退货）或 1~"
                    + com.ai.smartinventory.common.AfterSaleRule.MAX_RETURN_WINDOW_DAYS + " 天";
        }
        Integer noReason = product.getReturnNoReason();
        if (noReason != null && noReason != 0 && noReason != 1) {
            return "「是否支持无理由退货」取值有误";
        }
        return null;
    }

    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 【P1-1 限流补全】删除是破坏性操作，收紧。
    @RateLimit(key = "product:delete", permitsPerMinute = 20, permitsPerHour = 100)
    @DeleteMapping("/{id}")
    public Result<String> deleteProduct(@PathVariable Long id) {
        log.info("========== 开始删除商品 ==========");
        log.info("删除商品ID: {}", id);
        
        Product product = productService.getById(id);
        if (product == null) {
            log.warn("商品不存在，ID: {}", id);
            return Result.error("商品不存在");
        }
        
        // 【多商户隔离】归属校验：商家不得删除其他商家的商品
        MerchantContext.assertCanAccessMerchantData(product.getMerchantId());
        
        log.info("找到商品: id={}, name={}, deleted={}", product.getId(), product.getName(), product.getDeleted());
        
        boolean result = productService.removeById(id);
        
        if (result) {
            log.info("商品删除成功，ID: {}", id);
            return Result.success("删除成功");
        } else {
            log.error("商品删除失败，ID: {}", id);
            return Result.error(500, "删除失败");
        }
    }
    
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 【P1-1 限流补全】商品写接口，限流防批量刷。
    @RateLimit(key = "product:status", permitsPerMinute = 30, permitsPerHour = 300)
    @PutMapping("/status/{id}")
    public Result<String> updateProductStatus(@PathVariable Long id, @RequestParam Integer status) {
        Product product = productService.getById(id);
        if (product == null) {
            return Result.error("商品不存在");
        }
        // 【多商户隔离】上下架同样属于写操作，必须校验归属
        MerchantContext.assertCanAccessMerchantData(product.getMerchantId());
        product.setStatus(status);
        return productService.updateById(product) ? Result.success("状态更新成功") : Result.error(500, "状态更新失败");
    }
}