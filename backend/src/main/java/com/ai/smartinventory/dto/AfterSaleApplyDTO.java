package com.ai.smartinventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提交售后申请入参。
 *
 * 【刻意不做的事】不接受 userId / orderNo / refundAmount 上限之外的任何服务端字段：
 * 申请人与订单号一律由服务端按登录态与 orderId 推导，避免越权申请他人订单。
 */
@Data
public class AfterSaleApplyDTO {

    /** 申请售后的订单 ID */
    private Long orderId;

    /** 售后类型：1-仅退款 2-退货退款 3-换货 */
    private Integer type;

    /** 原因编码，见 AfterSaleRule.REASON_* */
    private String reasonCode;

    /** 补充说明 */
    private String description;

    /** 凭证图片 URL 列表（可选） */
    private List<String> images;

    /**
     * 申请退款金额。为空时服务端取订单实付金额。
     * 换货类型忽略该字段，强制为 0。
     */
    private BigDecimal refundAmount;

    /**
     * 按商品申请：本次售后的商品明细（可选）。
     *
     * 【兼容口径】为空 / 缺省 ⇒ 整单售后（item_scope=0），金额与回补行为
     * 与改造前逐字一致；非空 ⇒ 按商品售后（item_scope=1），金额上限改由
     * 各明细「单价 × 数量」合计推导。
     */
    private List<AfterSaleItemApplyDTO> items;
}
