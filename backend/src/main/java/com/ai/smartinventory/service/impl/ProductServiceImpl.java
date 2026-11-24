package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.ProductVisibility;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.Inventory;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.ProductCategory;
import com.ai.smartinventory.mapper.ProductCategoryMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.service.InventoryService;
import com.ai.smartinventory.service.ProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    @Lazy
    @Resource
    private InventoryService inventoryService;

    /** 分类名回填专用；独立 Mapper，避免为一个展示字段引入 Service 循环依赖 */
    @Resource
    private ProductCategoryMapper productCategoryMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        log.info("========== ProductServiceImpl.removeById ==========");
        log.info("尝试删除商品ID: {}", id);
        
        try {
            LambdaQueryWrapper<Inventory> inventoryWrapper = new LambdaQueryWrapper<>();
            inventoryWrapper.eq(Inventory::getProductId, id);
            int inventoryDeleted = inventoryService.remove(inventoryWrapper) ? 1 : 0;
            log.info("删除库存记录数量: {}", inventoryDeleted);
            
            boolean result = super.removeById(id);
            log.info("商品删除结果: {}", result);
            
            return result;
        } catch (Exception e) {
            log.error("删除商品失败，ID: {}", id, e);
            throw e;
        }
    }
    
    @Override
    public PageResult<Product> getProductPage(PageQueryDTO params) {
        try {
            Integer page = params.getPage() != null ? params.getPage() : 1;
            Integer size = params.getSize() != null ? params.getSize() : 10;
            String keyword = params.getKeyword() != null ? params.getKeyword() : "";
            Long categoryId = params.getCategoryId();
            String sort = params.getSort() != null ? params.getSort() : "createTime";
            String order = params.getOrder() != null ? params.getOrder() : "desc";
            
            // 【P1-3】统一夹取页码与 size 上限
            Page<Product> pageParam = new Page<>(PageBounds.page(page), PageBounds.size(size));
            LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
            
            // 【约定】不要再手写 .eq(Product::getDeleted, 0)。
            // 实体上已有 @TableLogic，MyBatis-Plus 会在生成的 SQL 里自动注入
            // `deleted = 0`；手写会产出 `WHERE deleted = 0 AND (deleted = 0 AND ...)`
            // 这样的重复条件（SQL 日志里可直接看到），纯属冗余。
            // 全项目同类冗余已一次性清理，勿再加回。
            
            // 【多商户隔离】商家只能看到自己名下的商品。
            // 管理员 / 普通用户（含未登录游客）currentMerchantId() 返回 null，
            // 视为「全局视图」，不加 merchant_id 条件。
            Long merchantId = MerchantContext.currentMerchantId();
            if (merchantId != null) {
                wrapper.eq(Product::getMerchantId, merchantId);
            }
            
            Integer status = params.getStatus();
            if (status != null && status >= 0) {
                wrapper.eq(Product::getStatus, status);
            }
            // 普通用户/游客侧：只展示已上架商品，避免把仓库里下架商品暴露出去
            else if (merchantId == null && !MerchantContext.hasGlobalView()) {
                wrapper.eq(Product::getStatus, 1);
            }
            
            if (keyword != null && !keyword.isEmpty()) {
                wrapper.and(w -> w.like(Product::getName, keyword)
                               .or()
                               .like(Product::getTags, keyword));
            }
            
            if (categoryId != null) {
                wrapper.eq(Product::getCategoryId, categoryId);
            }
            
            if ("price".equals(sort)) {
                wrapper.orderBy(true, "asc".equals(order), Product::getPrice);
            } else if ("sales".equals(sort)) {
                wrapper.orderBy(true, "desc".equals(order), Product::getSales);
            } else {
                wrapper.orderBy(true, "desc".equals(order), Product::getCreateTime);
            }
            
            Page<Product> result = this.page(pageParam, wrapper);
            // 【安全修复·S-2】买家/游客/其他商家不得看到成本价（管理员与所属商家保留）。
            ProductVisibility.maskCost(result.getRecords());
            // 【2026-10-10】回填分类名：exist=false 字段普通查询不会填，
            // 不补的话商品管理「分类」列、可视化分类列、雷达图、分类统计卡会一起空掉。
            fillCategoryNames(result.getRecords());
            return new PageResult<>(result.getTotal(), result.getRecords(), result.getCurrent(), result.getSize());
        } catch (Exception e) {
            // 【修复】原实现是：
            //     e.printStackTrace();
            //     return new PageResult<>(0L, List.of(), 1L, 10L);
            // 三个毛病叠在一起：
            //   1) 吞异常 —— 数据库挂了、SQL 写错了，前端照样收到 HTTP 200 +
            //      空列表，无法区分「没有商品」和「后端坏了」；
            //   2) 绕过 GlobalExceptionHandler —— 异常到不了全局处理器，上一轮
            //      统一异常处理（P0-2）在此处被架空；
            //   3) printStackTrace 写 stderr，不走 logback、不带请求上下文，
            //      且硬编码 size=10 忽略了调用方传入的 size。
            // 现改为「带上下文记日志 + 原样上抛」，交由 GlobalExceptionHandler
            // 收敛成统一 Result。
            log.error("商品分页查询失败 - page={}, size={}, merchantId={}, keyword={}",
                    params.getPage(), params.getSize(), MerchantContext.currentMerchantId(),
                    params.getKeyword(), e);
            throw e;
        }
    }
    
    @Override
    public List<Product> getHotProducts(Integer limit) {
        // 【P1-3】热门榜的 limit 同样夹取上限，防被传成百万级
        Page<Product> page = new Page<>(1, PageBounds.size(limit));
        Page<Product> result = this.page(page, new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, 1)
                .eq(Product::getIsHot, 1)
                .orderByDesc(Product::getSales));
        // 【安全修复·S-2】热门榜为买家侧接口，屏蔽成本价。
        return ProductVisibility.maskCost(fillCategoryNames(result.getRecords()));
    }

    @Override
    public List<Product> getRecommendProducts(Integer limit) {
        // 【P1-3】推荐榜的 limit 同样夹取上限
        Page<Product> page = new Page<>(1, PageBounds.size(limit));
        Page<Product> result = this.page(page, new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, 1)
                .eq(Product::getIsRecommend, 1)
                .orderByDesc(Product::getCreateTime));
        // 【安全修复·S-2】推荐榜为买家侧接口，屏蔽成本价。
        return ProductVisibility.maskCost(fillCategoryNames(result.getRecords()));
    }
    
    @Override
    public List<Product> getProductsByCategory(Long categoryId, Integer limit) {
        // 【P1-3】分类榜的 limit 同样夹取上限
        Page<Product> page = new Page<>(1, PageBounds.size(limit));
        Page<Product> result = this.page(page, new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, 1)
                .eq(Product::getCategoryId, categoryId)
                .orderByDesc(Product::getSales));
        // 【安全修复·S-2】分类榜为买家侧接口，屏蔽成本价。
        return ProductVisibility.maskCost(fillCategoryNames(result.getRecords()));
    }
    
    /**
     * 按增量调整 product.stock。
     *
     * 【修复】原实现是「getById 读 → 算 newStock → updateById 写」的非原子操作，
     * 与 InventoryServiceImpl 的扣减并发时会互相覆盖（丢失更新）。
     * 现改为数据库层原子 UPDATE，并把「不得为负」的判断下沉到 WHERE 条件。
     *
     * 【注意】该方法仍保留增量语义（供库存服务内部调用）；
     * 权威库存以 inventory 表为准，调用方应在同一事务内先改 inventory，
     * 再调用本方法同步 product.stock。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStock(Long productId, Integer quantity) {
        if (quantity == null) {
            throw new BusinessException(400, "库存变更量不能为空");
        }
        Product product = this.getById(productId);
        if (product == null) {
            throw new BusinessException(400, "商品不存在");
        }

        int affected;
        if (quantity >= 0) {
            affected = baseMapper.increaseStock(productId, quantity);
        } else {
            affected = baseMapper.deductStock(productId, -quantity);
        }
        if (affected == 0) {
            throw new BusinessException(400, "库存不足，当前仅剩 "
                    + (product.getStock() == null ? 0 : product.getStock()) + " 件");
        }
        return true;
    }

    /**
     * 批量回填 {@link Product#getCategoryName()}。
     *
     * <p>实现要点：
     * <ul>
     *   <li><b>一次批量查询</b>：先把列表里出现过的 categoryId 去重收集成 Set，
     *       再 {@code selectBatchIds} 一次取回，避免 N+1；</li>
     *   <li><b>分类被软删也不炸</b>：{@code ProductCategory} 带 {@code @TableLogic}，
     *       已删分类不会出现在结果里，此时该商品的 categoryName 保持 null，
     *       由前端按需显示占位，而不是抛异常；</li>
     *   <li><b>失败不阻断主流程</b>：分类名只是展示字段，查不到就留空，
     *       绝不能让一个展示字段把整个商品列表打成 500；</li>
     * </ul>
     */
    @Override
    public List<Product> fillCategoryNames(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return products == null ? Collections.emptyList() : products;
        }
        try {
            Set<Long> categoryIds = products.stream()
                    .map(Product::getCategoryId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (categoryIds.isEmpty()) {
                return products;
            }
            Map<Long, String> names = productCategoryMapper.selectBatchIds(categoryIds).stream()
                    .collect(Collectors.toMap(
                            ProductCategory::getId,
                            ProductCategory::getName,
                            (a, b) -> a,
                            java.util.HashMap::new));
            for (Product p : products) {
                if (p.getCategoryId() != null) {
                    p.setCategoryName(names.get(p.getCategoryId()));
                }
            }
        } catch (Exception e) {
            // 展示字段回填失败只记日志，不影响商品数据本身的返回
            log.warn("商品分类名回填失败（不影响主流程）: size={}", products.size(), e);
        }
        return products;
    }
}