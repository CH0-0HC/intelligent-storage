package com.ai.smartinventory.common;

import com.ai.smartinventory.entity.Product;

import java.util.List;

/**
 * 商品字段可见性的唯一出口（S-2 修复）。
 *
 * <p>背景：{@link Product} 实体被商品只读接口（{@code /product/list}、{@code /{id}}、
 * {@code /hot}、{@code /recommend}、{@code /category/{id}}）**原样序列化**返回，
 * 其中 {@code cost}（成本价）是商家经营敏感数据，却随匿名接口泄露给所有未登录访问者。
 *
 * <p>可见口径：成本价只对「管理员」与「该商品所属商家」可见；其余角色
 * （买家 / 未登录游客 / 其他商家 / 客服）一律置 {@code null}。
 *
 * <p><b>注意</b>：{@code stock}（库存）**不在此列** —— 买家侧需要它做库存提示与购买数量
 * 上限（{@code ProductList.vue} / {@code ProductDetail.vue}），屏蔽会直接打断买家下单。
 */
public final class ProductVisibility {

    private ProductVisibility() {
    }

    /**
     * 当前访问者是否可见指定商品的成本价。
     *
     * @param productMerchantId 商品的 merchant_id
     */
    public static boolean canSeeCost(Long productMerchantId) {
        // 管理员（全局视图）可看
        if (MerchantContext.hasGlobalView()) {
            return true;
        }
        // 仅商家角色的 currentMerchantId() 有值；买家/游客/客服均为 null
        Long mine = MerchantContext.currentMerchantId();
        return mine != null && mine.equals(productMerchantId);
    }

    /** 按访问者身份屏蔽成本价（就地修改），返回同一对象便于链式调用。 */
    public static Product maskCost(Product product) {
        if (product != null && !canSeeCost(product.getMerchantId())) {
            product.setCost(null);
        }
        return product;
    }

    /** 批量屏蔽成本价（就地修改），返回同一列表便于链式调用。 */
    public static List<Product> maskCost(List<Product> products) {
        if (products != null) {
            for (Product p : products) {
                maskCost(p);
            }
        }
        return products;
    }
}
