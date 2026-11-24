package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.Cart;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 购物车 Mapper
 */
@Mapper
public interface CartMapper extends BaseMapper<Cart> {

    /**
     * 查询用户购物车列表（关联商品信息）
     */
    @Select("SELECT c.*, p.name as product_name, p.image as product_image, " +
            "p.price as product_price, p.stock as product_stock " +
            "FROM cart c LEFT JOIN product p ON c.product_id = p.id " +
            "WHERE c.user_id = #{userId} AND c.deleted = 0 AND p.deleted = 0 " +
            "ORDER BY c.create_time DESC")
    List<Cart> selectByUserIdWithProduct(@Param("userId") Long userId);

    /**
     * 【修复】查询「包含已逻辑删除」的购物车行。
     *
     * 背景：cart 表有唯一键 uk_user_product(user_id, product_id)，而 @TableLogic
     * 是逻辑删除（deleted=1）——软删的行物理上仍然占着这个唯一键。
     * 于是「删掉商品 → 再加同一商品」时，MyBatis-Plus 因为自动过滤 deleted=0
     * 查不到旧行，走去 INSERT，直接撞唯一键 → DuplicateKeyException → 500。
     *
     * 这里必须手写 SQL 绕过 @TableLogic 的自动过滤，才能看见那行软删记录。
     */
    @Select("SELECT * FROM cart WHERE user_id = #{userId} AND product_id = #{productId} " +
            "ORDER BY id DESC LIMIT 1")
    Cart selectAnyByUserAndProduct(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * 恢复一条被逻辑删除的购物车行（复用它，而不是插新行）。
     *
     * 【2026-09-22】同时把 selected 置回 1：用户是「重新加入购物车」，
     * 语义上等同新增，应默认勾选（与 cart.selected 的列默认值一致）。
     */
    @Update("UPDATE cart SET deleted = 0, quantity = #{quantity}, selected = 1, update_time = NOW() WHERE id = #{id}")
    int restoreCart(@Param("id") Long id, @Param("quantity") Integer quantity);
}