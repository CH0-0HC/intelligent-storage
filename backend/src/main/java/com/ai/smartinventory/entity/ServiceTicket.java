package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 客服裁定工单 —— 超出商品退货期限后的**唯一**放行通道。
 *
 * 【它解决什么】
 * 改造前售后时效是全站写死的 15 天，超期即彻底不可申请：买家没有任何
 * 「还能不能争取一下」的入口，客服在系统里也无处受理。
 * 现在商家为每个商品指定退货期限（{@code product.return_window_days}），
 * 超期后买家先提工单 → 客服按商家统一标准裁定 → 通过后买家才可继续申请售后。
 *
 * 【状态机（刻意做得比 after_sale 简单）】
 * <pre>
 *   0 待客服裁定 ──裁定通过(resolution=1)──▶ 1 已裁定（授权可用）
 *                ──裁定驳回(resolution=2)──▶ 1 已裁定（不可用，可重新提工单）
 *                ──买家主动撤销──────────▶ 2 已取消
 * </pre>
 * 「已裁定」不再细分通过与驳回，因为可读性由 {@code resolution} 承担；
 * 状态只表达「还等不等客服」。
 *
 * 【与 after_sale 的关系】
 * 工单本身**不动钱、不动库存**。只有裁定通过、买家据此提交售后之后，
 * 才进入 after_sale 的退款/回补流程。因此工单可以放心地允许重复提交
 * （被驳回后允许再提），不会像售后单那样带来刷库存的风险。
 *
 * 【autoResultMap = true 的必要性】images 是 JSON 列，见 {@link AfterSale} 的同类说明。
 */
@Data
@TableName(value = "service_ticket", autoResultMap = true)
public class ServiceTicket {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单号，形如 CS20260928xxxxxx0001 */
    private String ticketNo;

    /** 关联订单 */
    private Long orderId;

    /** 订单号冗余，列表展示用 */
    private String orderNo;

    /** 关联订单明细（按商品咨询时非空；整单咨询为 null） */
    private Long orderItemId;

    /** 商品 ID（null 表示整单咨询） */
    private Long productId;

    /** 商品所属商家 ID（申请时快照，裁定按商家口径执行） */
    private Long merchantId;

    /** 提交工单的买家 */
    private Long userId;

    /** 诉求原因编码，复用 {@code AfterSaleRule.REASON_*} 字典，避免两套文案 */
    private String reasonCode;

    /** 买家补充说明 */
    private String description;

    /** 凭证图片 URL 列表，落库为 JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 该商品退货期限（天），申请时快照 —— 便于客服一眼看出「超了多久」 */
    private Integer returnWindowDays;

    /** 超期天数（申请时计算并快照） */
    private Integer daysOverdue;

    /** 0-待客服裁定 1-已裁定 2-用户已取消 */
    private Integer status;

    /** 裁定结果：1-允许继续申请售后，2-驳回申请 */
    private Integer resolution;

    /** 客服裁定说明（统一标准依据，双方可见） */
    private String csRemark;

    /** 裁定客服 ID */
    private Long operatorId;

    /** 裁定时间 */
    private LocalDateTime operateTime;

    /** 放行授权是否已使用：0-未使用，1-已使用（已写入 after_sale.cs_ticket_id） */
    private Integer consumed;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ==================== 非持久化展示字段 ====================

    /** 商品名称（列表展示，由服务层批量回填） */
    @TableField(exist = false)
    private String productName;

    /** 买家用户名/昵称（客服工作台展示） */
    @TableField(exist = false)
    private String buyerName;

    /** 客服姓名（买家侧展示「谁处理的」） */
    @TableField(exist = false)
    private String operatorName;

    /** 订单实付金额（客服判断诉求合理性时参考） */
    @TableField(exist = false)
    private java.math.BigDecimal orderPayAmount;
}
