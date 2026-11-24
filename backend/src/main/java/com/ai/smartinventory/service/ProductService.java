package com.ai.smartinventory.service;

import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.Product;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

public interface ProductService extends IService<Product> {
    
    PageResult<Product> getProductPage(PageQueryDTO params);
    
    List<Product> getHotProducts(Integer limit);
    
    List<Product> getRecommendProducts(Integer limit);
    
    List<Product> getProductsByCategory(Long categoryId, Integer limit);
    
    boolean updateStock(Long productId, Integer quantity);

    /**
     * 批量回填商品的分类名（{@code Product.categoryName}）。
     *
     * <p><b>为什么需要这个方法：</b>{@code categoryName} 在实体上是
     * {@code @TableField(exist=false)} 的**展示字段**，不落库、普通查询不会填充。
     * 2026-10-10 实测发现 {@code GET /api/product/list} 一直返回 {@code categoryName=null}，
     * 导致前端 4 处同时失效：
     * <ul>
     *   <li>商品管理「分类」列全空；</li>
     *   <li>数据可视化「库存预警商品明细」的分类列全空；</li>
     *   <li>库存分布雷达图把所有商品归进「未分类」，退化成单维度；</li>
     *   <li>「商品分类」统计卡恒为 0（实际应为 5）。</li>
     * </ul>
     * 本方法按 {@code categoryId} 批量查一次 {@code product_category} 后统一赋值，
     * N 条商品只多一次查询（不是 N+1）。分类名查不到时保持 null，交由前端按需兜底。
     *
     * <p>{@code AiController} 的品类聚合早就在自己做同样的映射（见那里 361 行的注释），
     * 属于同一个坑被绕开而非修掉；现在统一收到这里。
     *
     * @param products 待回填的商品列表，允许为 null 或空（此时原样返回）
     * @return 入参本身，便于链式调用
     */
    List<Product> fillCategoryNames(List<Product> products);
}