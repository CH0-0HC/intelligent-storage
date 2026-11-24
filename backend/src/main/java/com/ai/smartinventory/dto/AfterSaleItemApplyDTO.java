package com.ai.smartinventory.dto;

import lombok.Data;

/**
 * 按商品申请售后的单行入参。
 *
 * 【只收三个字段】productId / 价格 / 商家 一律由服务端按 orderItemId 推导，
 * 与 {@link AfterSaleApplyDTO} 的既有原则一致（不接受任何可由服务端推导的字段，
 * 避免越权申请他人订单 / 篡改金额）。
 */
@Data
public class AfterSaleItemApplyDTO {

    /** 订单明细 ID（order_item.id），必须属于本次申请的订单 */
    private Long orderItemId;

    /** 本次申请数量（>0，且不超过 order_item.quantity） */
    private Integer quantity;

    /** 该商品的备注（可选） */
    private String remark;
}
