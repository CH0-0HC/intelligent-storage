package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后申请明细（按商品粒度）。
 *
 * 【背景】{@link AfterSale} 只有 order_id，是整单粒度：混合订单下商家 A 能处理
 * 含自家商品的整单售后（含商家 B 的商品），属跨商户越权残余面（审计 P1-6）。
 * 本实体把「本次售后到底退哪些商品、每样几件」落成数据，供：
 *   · 归属校验按商品粒度做；
 *   · 库存回补按 item 的 quantity 精确回补（不再整单全量）；
 *   · 幂等键从「订单级」细化为「订单+商品级」。
 *
 * 【兼容口径】售后单 {@code item_scope=0}（或本表无该售后单的行）时仍是整单语义，
 * 与改造前逐字一致。
 *
 * 【为什么没有 refund_amount，只有 amount】
 * 整单退款额仍在 {@code after_sale.refund_amount}；本表 amount 是「行金额 =
 * order_item.price × quantity」，服务端计算。两处金额口径分工明确，避免打架。
 *
 * 【为什么 merchant_id 存快照】
 * 归属校验若实时 join product，商品被物理删除后 INNER JOIN 会静默丢行，
 * 导致「校验通过但没有明细」的漏洞。快照 + product 实时值二者取「或」更稳
 * （见 AfterSaleItemMapper.selectByAfterSaleAndMerchant）。
 */
@Data
@TableName("after_sale_item")
public class AfterSaleItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属售后单 ID（after_sale.id） */
    private Long afterSaleId;

    /** 关联订单 ID（冗余，便于按订单聚合 / 幂等） */
    private Long orderId;

    /** 订单明细 ID（order_item.id，快照来源） */
    private Long orderItemId;

    /** 商品 ID（冗余，库存回补 / 归属校验直接可用） */
    private Long productId;

    /** 商品所属商家 ID（申请时从 product 快照；null 表示商品已物理删除） */
    private Long merchantId;

    /** 本次申请数量（>0，且不超过 order_item.quantity） */
    private Integer quantity;

    /** 本行金额 = order_item.price × quantity（服务端计算） */
    private BigDecimal amount;

    /** 备注（如需按商品说明原因） */
    private String remark;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
