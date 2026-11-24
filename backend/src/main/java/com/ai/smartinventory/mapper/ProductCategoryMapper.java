package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.ProductCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品分类 Mapper。
 *
 * 【2026-09-23 新增】数据可视化（Charts.vue）需要把订单销售额按「品类」聚合，
 * 而 Product.categoryName 是 @TableField(exist=false) 的展示字段，
 * 普通查询不会填充 —— 这里直接查 product_category 建立 categoryId → 名称映射。
 */
@Mapper
public interface ProductCategoryMapper extends BaseMapper<ProductCategory> {
}
