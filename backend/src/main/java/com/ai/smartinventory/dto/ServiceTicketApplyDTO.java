package com.ai.smartinventory.dto;

import lombok.Data;

import java.util.List;

/**
 * 提交客服工单入参（买家侧）。
 *
 * <p>与 {@link AfterSaleApplyDTO} 的分工：这里只表达「我要申诉，请客服裁定」，
 * 不涉及退款金额与售后类型 —— 那些要等客服放行后，在正式的售后申请里再填。
 */
@Data
public class ServiceTicketApplyDTO {

    /** 关联订单（必填） */
    private Long orderId;

    /**
     * 关联订单明细（可选）。按商品申诉时传，用于定位「哪个商品超期了」；
     * 为空表示整单申诉。
     */
    private Long orderItemId;

    /**
     * 商品 ID（可选，缺省由 orderItemId 推导）。必须属于该订单。
     */
    private Long productId;

    /** 诉求原因编码，复用 AfterSaleRule 的原因字典（必填） */
    private String reasonCode;

    /** 补充说明（<=500 字） */
    private String description;

    /** 凭证图片（最多 6 张） */
    private List<String> images;
}
