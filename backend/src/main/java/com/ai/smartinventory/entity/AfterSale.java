package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后申请单。
 *
 * 【背景】本实体对应的是「确认收货后申请售后」这一缺失的闭环。
 * 在此之前项目中不存在任何售后域的表 / 实体 / 接口，用户确认收货后
 * 前端订单列表只剩「再次购买」，没有任何申请售后的入口。
 *
 * 【autoResultMap = true 的必要性】
 * images 是 JSON 列，用 {@link JacksonTypeHandler} 做 List&lt;String&gt; ↔ JSON 映射。
 * MyBatis-Plus 只有在 autoResultMap = true 时才会为实体生成带 typeHandler 的
 * resultMap；漏写会导致查询回来的 images 恒为 null（插入正常、查询丢失）。
 *
 * 【为什么没有 merchant_id】
 * 一个订单可能包含多个商家的商品，售后单挂单一 merchant_id 会表达不准确。
 * 商家侧数据范围统一走「order → order_item → product.merchant_id 反查」。
 */
@Data
@TableName(value = "after_sale", autoResultMap = true)
public class AfterSale {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 售后单号，形如 AS20260924xxxxx */
    private String afterSaleNo;

    /** 关联订单 */
    private Long orderId;

    /** 订单号冗余，列表展示用（订单被改号/删单时仍可追溯） */
    private String orderNo;

    /** 申请用户（买家） */
    private Long userId;

    /** 售后类型：1-仅退款 2-退货退款 3-换货 */
    private Integer type;

    /** 原因编码，取值见 {@code common.AfterSaleRule.REASON_*} */
    private String reasonCode;

    /** 原因展示文案（服务端按编码生成，避免前端各自翻译） */
    private String reason;

    /** 买家补充说明 */
    private String description;

    /** 凭证图片 URL 列表，落库为 JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 申请退款金额；换货为 0 */
    private BigDecimal refundAmount;

    /**
     * 售后范围：0-整单（历史数据 / 缺省，兼容旧语义），1-按商品（after_sale_item 有明细）。
     *
     * ⚠️ 持久化字段，必须与 db/init.sql 第 22 节、db/after_sale.sql 的 ALTER 完全一致。
     * 本字段落地前，线上库必须先执行 item_scope 的 ALTER：MyBatis-Plus 默认 SELECT
     * 全字段，库缺列会让**所有售后查询直接 500**（本项目已因同类漂移踩坑三次，
     * 见 init.sql 中 order / purchase_order / cart 的记录）。
     */
    private Integer itemScope;

    /**
     * 【2026-09-28 新增】客服裁定工单 ID —— 超期售后的**一次性放行凭证**。
     *
     * <p>常规申请（在商品退货期限内）为 {@code null}；超出商品退货期限时，
     * 买家必须先提交客服工单（{@code service_ticket}），客服裁定「允许继续申请」
     * 后才可提交售后，并把那张工单的 id 写在这里，作为可追溯依据。
     *
     * <p>服务端提交时会校验工单归属本人 + 属于本订单 + 已裁定通过 + {@code consumed=0}，
     * 并在落库后把工单置为已消费，保证一张工单只放行一次。
     *
     * ⚠️ 持久化字段，必须与 db/init.sql 第 22 节、db/after_sale.sql 的 ALTER 一致。
     */
    private Long csTicketId;

    /** 售后状态：0-待处理 1-商家已同意 2-已完成 3-商家已拒绝 4-买家已撤销 */
    private Integer status;

    /** 申请时间 */
    private LocalDateTime applyTime;

    /** 商家处理时间 */
    private LocalDateTime auditTime;

    /** 商家处理备注 / 拒绝原因 */
    private String auditRemark;

    /** 售后完成时间 */
    private LocalDateTime finishTime;

    /** 处理人（商家/管理员）ID */
    private Long operatorId;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ==================== 非持久化展示字段 ====================

    /**
     * 关联订单的明细（下单时的商品快照）。商家处理售后时需要看到
     * 「退的是哪个商品、几件」，由 AfterSaleServiceImpl 在处理列表里批量回填。
     *
     * ⚠️【语义红线】**永远是整单明细**，不是售后明细。
     * 商家视角的「本商家明细」是另一个字段 {@link #myItems}。
     * 把本字段过滤成本商家会让 verify-after-sale.mjs 的断言失效（混合订单下会返回空数组）。
     */
    @TableField(exist = false)
    private List<OrderItem> items;

    /**
     * 本次售后申请的商品明细（item_scope=1 时有值；=0 时为 null，前端回退「整单」渲染）。
     * 由 AfterSaleServiceImpl.enrich 批量回填。
     */
    @TableField(exist = false)
    private List<AfterSaleItem> saleItems;

    /** 商家视角：本商家在该售后单中涉及的明细（仅 handle / manage 场景回填） */
    @TableField(exist = false)
    private List<AfterSaleItem> myItems;

    /** 买家用户名/昵称，商家处理列表展示用 */
    @TableField(exist = false)
    private String buyerName;

    /** 订单实付金额，商家判断退款金额是否合理时参考 */
    @TableField(exist = false)
    private BigDecimal orderPayAmount;
}
