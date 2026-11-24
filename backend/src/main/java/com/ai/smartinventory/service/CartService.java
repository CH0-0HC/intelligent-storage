package com.ai.smartinventory.service;

import com.ai.smartinventory.entity.Cart;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 购物车服务接口
 */
public interface CartService extends IService<Cart> {

    /**
     * 获取用户购物车列表（含商品信息）
     */
    List<Cart> getUserCart(Long userId);

    /**
     * 添加商品到购物车
     */
    boolean addToCart(Long userId, Long productId, Integer quantity);

    /**
     * 更新购物车商品数量
     */
    boolean updateQuantity(Long cartId, Long userId, Integer quantity);

    /**
     * 从购物车中移除商品
     */
    boolean removeFromCart(Long cartId, Long userId);

    /**
     * 清空用户购物车
     */
    boolean clearCart(Long userId);

    // ==================== 勾选（2026-09-22 新增） ====================
    // 勾选状态持久化在服务端，因此「刷新页面 / 重新登录后仍然保持」。

    /**
     * 设置单条购物车记录的勾选状态。
     *
     * @param selected 1-选中，0-未选中
     */
    boolean updateSelected(Long cartId, Long userId, Integer selected);

    /**
     * 批量设置当前用户购物车的勾选状态（「全选 / 取消全选」）。
     *
     * @return 实际更新的行数
     */
    int updateSelectedAll(Long userId, Integer selected);

    /**
     * 移除当前用户购物车中**已勾选**的记录。
     *
     * 用于「部分结算」：下单只提交勾选项，成功后就只能移除勾选项 ——
     * 若沿用 clearCart() 会把未勾选的商品一起删掉（数据丢失）。
     *
     * @return 实际移除的行数
     */
    int removeSelected(Long userId);

    /**
     * 按商品 ID 精确移除购物车记录 —— 下单事务内调用，只清理**本单**商品。
     *
     * 【数据丢失修复·2026-09-22】createOrderWithItems 原先在下单成功后调用
     * clearCart(userId) 整单清空：部分结算（只勾选部分商品下单）时，
     * 未勾选、并未购买的商品会一并被删 —— 表现为“下单后再打开购物车，
     * 没买的东西全没了”。这里按本单明细的 productId 精确移除，
     * 未购买商品必须保留（与淘宝/京东语义一致）。
     *
     * @return 实际移除的行数
     */
    int removeByProductIds(Long userId, List<Long> productIds);
}