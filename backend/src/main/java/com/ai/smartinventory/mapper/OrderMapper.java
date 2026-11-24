package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.Order;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    
    List<Order> selectByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // ==================== 多商户隔离支撑查询 ====================
    // 订单表本身没有 merchant_id 字段，商家与订单的归属关系只能通过
    // order_item.product_id → product.merchant_id 反查得到。

    /**
     * 查询「包含指定商家商品」的所有订单 ID。
     * 用于商家订单列表的数据范围限定。
     */
    @Select("SELECT DISTINCT oi.order_id FROM order_item oi " +
            "JOIN product p ON p.id = oi.product_id " +
            "WHERE p.merchant_id = #{merchantId} AND oi.deleted = 0 AND p.deleted = 0")
    List<Long> selectOrderIdsByMerchantId(@Param("merchantId") Long merchantId);

    /**
     * 判断某订单是否包含指定商家的商品，返回命中条数（>0 即有权访问）。
     * 用于订单详情/发货/取消等单条操作前的归属校验。
     */
    @Select("SELECT COUNT(1) FROM order_item oi " +
            "JOIN product p ON p.id = oi.product_id " +
            "WHERE oi.order_id = #{orderId} AND p.merchant_id = #{merchantId} " +
            "AND oi.deleted = 0 AND p.deleted = 0")
    long countOrderOwnedByMerchant(@Param("orderId") Long orderId, @Param("merchantId") Long merchantId);
}
