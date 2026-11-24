package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.UserBehaviorRecorder;
import com.ai.smartinventory.entity.Cart;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.mapper.CartMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.service.CartService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 购物车服务实现
 */
@Slf4j
@Service
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements CartService {

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductMapper productMapper;

    /** R18：用户行为埋点（加购 → cart） */
    @Resource
    private UserBehaviorRecorder userBehaviorRecorder;

    @Override
    public List<Cart> getUserCart(Long userId) {
        return cartMapper.selectByUserIdWithProduct(userId);
    }

    @Override
    @Transactional
    public boolean addToCart(Long userId, Long productId, Integer quantity) {
        // 校验商品是否存在、上架状态、库存
        Product product = productMapper.selectById(productId);
        if (product == null || product.getDeleted() == 1) {
            throw new BusinessException(400, "商品不存在或已下架");
        }
        if (product.getStatus() != 1) {
            throw new BusinessException(400, "商品已下架，无法购买");
        }
        if (product.getStock() == null || product.getStock() <= 0) {
            throw new BusinessException(400, "商品库存不足，暂时无法购买");
        }
        if (quantity != null && quantity > product.getStock()) {
            throw new BusinessException(400, "购买数量超出库存，当前库存: " + product.getStock());
        }

        // 检查是否已存在（@TableLogic自动过滤deleted=0）
        Cart existing = this.getOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getProductId, productId));

        if (existing != null) {
            int newQty = existing.getQuantity() + (quantity != null ? quantity : 1);
            if (newQty > product.getStock()) {
                throw new BusinessException(400, "购物车已有该商品，累计数量超出库存，当前库存: " + product.getStock());
            }
            existing.setQuantity(newQty);
            boolean updated = this.updateById(existing);
            if (updated) {
                userBehaviorRecorder.record(userId, UserBehaviorRecorder.TYPE_CART, productId);
            }
            return updated;
        }

        // 【修复】唯一键 与 逻辑删除 的冲突（实测复现过 500）
        //
        // cart 表有唯一键 uk_user_product(user_id, product_id)，而删除走的是
        // @TableLogic 逻辑删除 —— 软删的行物理上仍在，继续占着这个唯一键。
        // 上面的 getOne 因自动过滤 deleted=0 查不到它，于是代码走到 INSERT，
        // 直接撞唯一键抛 DuplicateKeyException，被兜底成 500「系统繁忙」。
        // 现象：删掉购物车里的商品后，再加同一商品永远失败（且看不出原因）。
        //
        // 正确做法：不复用新行，而是把那条软删行「复活」。
        Cart softDeleted = cartMapper.selectAnyByUserAndProduct(userId, productId);
        if (softDeleted != null) {
            int restoreQty = quantity != null ? quantity : 1;
            boolean restored = cartMapper.restoreCart(softDeleted.getId(), restoreQty) > 0;
            if (restored) {
                userBehaviorRecorder.record(userId, UserBehaviorRecorder.TYPE_CART, productId);
            }
            return restored;
        }

        // 新增
        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setProductId(productId);
        cart.setQuantity(quantity != null ? quantity : 1);
        // 新加入的商品默认勾选（与主流电商一致，也与 cart.selected 的列默认值一致）。
        // 这里显式赋值而不是依赖库默认值：MyBatis-Plus 默认跳过 null 字段，
        // 依赖 DDL 默认值也能跑通，但显式写出意图更清楚、也不怕将来列默认值被改。
        cart.setSelected(1);
        boolean saved = this.save(cart);
        if (saved) {
            userBehaviorRecorder.record(userId, UserBehaviorRecorder.TYPE_CART, productId);
        }
        return saved;
    }

    @Override
    public boolean updateQuantity(Long cartId, Long userId, Integer quantity) {
        Cart cart = this.getOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getId, cartId)
                .eq(Cart::getUserId, userId));

        if (cart == null) {
            throw new BusinessException(404, "购物车记录不存在");
        }

        if (quantity <= 0) {
            return removeFromCart(cartId, userId);
        }

        cart.setQuantity(quantity);
        return this.updateById(cart);
    }

    @Override
    public boolean removeFromCart(Long cartId, Long userId) {
        // @TableLogic注解下，逻辑删除必须使用removeById，不能用updateById手动设置deleted
        Cart cart = this.getOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getId, cartId)
                .eq(Cart::getUserId, userId));

        if (cart == null) {
            throw new BusinessException(404, "购物车记录不存在");
        }

        return this.removeById(cart.getId());
    }

    @Override
    @Transactional
    public boolean clearCart(Long userId) {
        List<Cart> items = this.list(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId));

        if (items.isEmpty()) {
            return true;
        }

        // @TableLogic下使用removeByIds自动转换为逻辑删除
        List<Long> ids = items.stream().map(Cart::getId).collect(Collectors.toList());
        return this.removeByIds(ids);
    }

    // ==================== 勾选（2026-09-22 新增） ====================

    /**
     * 设置单条购物车记录的勾选状态。
     *
     * 幂等处理：若该行已经是目标状态，直接返回成功、不发 UPDATE。
     * 理由：MySQL 默认返回「受影响行数」，值未变化时该值为 0 ——
     * 若按「UPDATE 影响行数 > 0」判断成功，用户重复点同一个勾选会收到「更新失败」的假报错。
     */
    @Override
    public boolean updateSelected(Long cartId, Long userId, Integer selected) {
        int target = normalizeSelected(selected);

        Cart cart = this.getOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getId, cartId)
                .eq(Cart::getUserId, userId));

        if (cart == null) {
            // 不存在、或不属于当前用户，一律同一错误，避免被枚举他人购物车 id
            throw new BusinessException(404, "购物车记录不存在");
        }
        if (Objects.equals(cart.getSelected(), target)) {
            return true;
        }

        // 归属条件同时写在 WHERE 里（纵深防御），即使上面查过了也不会误改他人数据
        return this.update(new LambdaUpdateWrapper<Cart>()
                .eq(Cart::getId, cartId)
                .eq(Cart::getUserId, userId)
                .set(Cart::getSelected, target));
    }

    /**
     * 批量设置勾选状态（全选 / 取消全选）。
     *
     * 只更新状态确实不同的行，避免无谓写库与 update_time 抖动。
     *
     * @return 实际发生变更的行数（原本已是目标状态的不计入）
     */
    @Override
    public int updateSelectedAll(Long userId, Integer selected) {
        int target = normalizeSelected(selected);

        List<Cart> items = this.list(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId));

        List<Long> needUpdate = items.stream()
                .filter(i -> !Objects.equals(i.getSelected(), target))
                .map(Cart::getId)
                .collect(Collectors.toList());

        if (needUpdate.isEmpty()) {
            return 0;
        }

        this.update(new LambdaUpdateWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .in(Cart::getId, needUpdate)
                .set(Cart::getSelected, target));

        return needUpdate.size();
    }

    /**
     * 移除当前用户已勾选的购物车记录（部分结算后调用）。
     *
     * 为什么不能沿用 clearCart()：单次结算可能只买了勾选中的几件，
     * 清空整个购物车会把未勾选的商品一并删掉，属于静默数据丢失。
     */
    @Override
    @Transactional
    public int removeSelected(Long userId) {
        List<Cart> selectedItems = this.list(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getSelected, 1));

        if (selectedItems.isEmpty()) {
            return 0;
        }

        List<Long> ids = selectedItems.stream().map(Cart::getId).collect(Collectors.toList());
        this.removeByIds(ids);
        return ids.size();
    }

    /**
     * 按商品 ID 精确移除购物车记录（下单事务内调用，只清理本单商品）。
     *
     * 【数据丢失修复·2026-09-22】替代下单流程里的 clearCart(userId)：
     * 整单清空会把未勾选、未购买的商品一起删掉。这里按 productId 精确匹配，
     * 只删本单买到的；未购买商品原样保留。
     */
    @Override
    @Transactional
    public int removeByProductIds(Long userId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return 0;
        }

        List<Cart> orderedRows = this.list(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .in(Cart::getProductId, productIds));

        if (orderedRows.isEmpty()) {
            return 0;
        }

        List<Long> ids = orderedRows.stream().map(Cart::getId).collect(Collectors.toList());
        this.removeByIds(ids);
        return ids.size();
    }

    /**
     * 勾选状态只接受 0 / 1；null 或其它值明确报 400，
     * 不静默当成某个默认值（否则前端传出 undefined 会被当作「取消勾选」）。
     */
    private int normalizeSelected(Integer selected) {
        if (selected == null) {
            throw new BusinessException(400, "请指定勾选状态");
        }
        if (selected != 0 && selected != 1) {
            throw new BusinessException(400, "勾选状态只能是 0（未选）或 1（选中）");
        }
        return selected;
    }
}