package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.AfterSaleItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AfterSaleItemMapper extends BaseMapper<AfterSaleItem> {

    /**
     * 某订单某商品【已生效】的售后数量合计（用于「不可超退」校验）。
     *
     * 【为什么排除 3-已拒绝 / 4-已撤销】这两态允许买家在剩余时效内重新申请
     * （见 AfterSaleRule 与 AfterSaleRuleTest 的 rejectedCanReapply / cancelledCanReapply），
     * 若计入会把该商品的额度永久锁死 —— 买家一旦被拒就再也退不了。
     * 因此只统计 status in (0-待处理, 1-商家已同意, 2-已完成)。
     */
    @Select("SELECT COALESCE(SUM(asi.quantity),0) FROM after_sale_item asi " +
            "JOIN after_sale asa ON asa.id = asi.after_sale_id " +
            "WHERE asi.order_id = #{orderId} AND asi.product_id = #{productId} " +
            "AND asi.deleted = 0 AND asa.deleted = 0 AND asa.status IN (0,1,2)")
    int sumClaimedQuantity(@Param("orderId") Long orderId, @Param("productId") Long productId);

    /**
     * 按（售后单, 商家）取该商家名下的售后明细行（归属校验 / 可见性过滤用）。
     *
     * 【为什么是 p.merchant_id OR asi.merchant_id】兜住两种历史情形：
     *   · 商品改过归属 —— 快照（asi.merchant_id）仍是当时的值；
     *   · 商品被物理删除 —— JOIN product 会丢行，靠快照兜住。
     * 二者任一命中即认，避免「校验通过却没有明细」或「有明细却看不到」。
     */
    @Select("SELECT asi.* FROM after_sale_item asi " +
            "LEFT JOIN product p ON p.id = asi.product_id AND p.deleted = 0 " +
            "WHERE asi.after_sale_id = #{afterSaleId} AND asi.deleted = 0 " +
            "AND (p.merchant_id = #{merchantId} OR asi.merchant_id = #{merchantId})")
    List<AfterSaleItem> selectByAfterSaleAndMerchant(@Param("afterSaleId") Long afterSaleId,
                                                     @Param("merchantId") Long merchantId);
}
