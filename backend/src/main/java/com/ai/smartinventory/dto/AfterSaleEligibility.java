package com.ai.smartinventory.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单的售后可申请状态 —— 前端「申请售后」入口的**唯一**展示依据。
 *
 * 【为什么由后端算】
 * 入口是否出现、是否置灰、点击后弹什么提示，三件事必须同源。
 * 前端只做渲染：{@code eligible = true} 表示可提交；
 * 否则用 {@code hint} 直接作为置灰按钮的 tooltip，不再自行拼文案。
 *
 * 该对象会被挂到订单列表的每个 Order 上（Order.afterSale），
 * 前端因此**不需要**为每个订单再发一次资格查询请求。
 */
@Data
public class AfterSaleEligibility {

    /** 当前是否可提交售后申请 */
    private Boolean eligible = Boolean.FALSE;

    /** 结果码，取值见 AfterSaleRule.CODE_*，供前端做分支（如区分「已过期」与「处理中」） */
    private String code;

    /** 面向用户的提示文案，前端直接展示，勿在前端重写 */
    private String hint;

    /** 该订单是否已存在售后单（含已完成 / 已拒绝 / 已撤销） */
    private Boolean hasAfterSale = Boolean.FALSE;

    /** 是否已超出申请时效 */
    private Boolean expired = Boolean.FALSE;

    /** 确认收货时间缺失、已用更新时间兜底推算（历史脏数据） */
    private Boolean degraded = Boolean.FALSE;

    /** 距售后申请期结束剩余天数（仅 eligible=true 时有意义） */
    private Integer daysLeft;

    /** 距「7 天无理由」结束剩余天数；0 表示只能选有理由类型 */
    private Integer noReasonDaysLeft;

    /** 最近一条售后单 ID，供前端「查看售后」跳转 */
    private Long latestAfterSaleId;

    /** 最近一条售后单状态 */
    private Integer latestStatus;

    /** 最近一条售后单类型 */
    private Integer latestType;

    // ==================== 商品退货规则 + 客服通道（2026-09-28 新增） ====================

    /**
     * 本订单适用的退货退款期限（天）：订单内商品期限的**最小值**（最严一档）；
     * {@code 0} 表示该商品不支持退货退款。
     * 前端在「申请售后」页与购买界面据此展示规则文案。
     */
    private Integer returnWindowDays;

    /** 该期限是否是商家显式配置的（false = 未配置，按默认 15 天兜底） */
    private Boolean returnWindowConfigured = Boolean.FALSE;

    /**
     * 该订单商品是否允许「无理由」退货。
     *
     * <p>为 false 时原因下拉须禁用「无理由」项 —— 注意它与
     * {@link #noReasonDaysLeft} 是**两个独立维度**：
     * 前者是商家的品类政策（该商品压根不接无理由），
     * 后者是时间（没超出无理由期）。
     */
    private Boolean returnNoReasonAllowed = Boolean.TRUE;

    /**
     * 是否应引导用户「先联系客服」。
     *
     * <p>为 true 的典型场景：已超出商品退货期限，但该商品支持退货 ——
     * 此时用户不是「彻底不能退」，而是必须先走客服裁定。
     * 前端据此把入口从置灰改成**可点击的「联系客服」**。
     */
    private Boolean csRequired = Boolean.FALSE;

    /** 是否已获得客服放行授权（可继续提交售后） */
    private Boolean csApproved = Boolean.FALSE;

    /** 可用的放行工单 ID，供前端「继续申请」直连 */
    private Long csTicketId;

    /** 可用的放行工单号（展示用） */
    private String csTicketNo;

    /** 放行授权的有效期截止时间；仅 csApproved=true 时有意义 */
    private LocalDateTime csAuthorizationDeadline;

    /**
     * 入口形态，由后端一次性裁定，前端只渲染：
     *   APPLY    —— 可点击「申请售后」
     *   VIEW     —— 显示「查看售后」（已有售后单）
     *   CS       —— 显示**可点击**的「联系客服」（超期，需客服裁定）
     *   DISABLED —— 置灰，具体原因看 hint
     *
     * 【R15】写成派生属性而不是在 AfterSaleRule 的 8 个 return 点各 set 一次，
     * 是为了避免漏改其中一处导致入口形态与 eligible 不一致。
     * Lombok 不会为已存在的方法再生成同名 getter，Jackson 会把它序列化为 entryState。
     *
     * 【2026-09-28】插入 CS 分支：放在 hasAfterSale 之前 —— 超期且已有被驳回/撤销的
     * 历史售后单时，用户真正该做的是联系客服，而不是反复点进那张旧单。
     */
    public String getEntryState() {
        if (Boolean.TRUE.equals(eligible)) {
            return "APPLY";
        }
        if (Boolean.TRUE.equals(csRequired)) {
            return "CS";
        }
        if (Boolean.TRUE.equals(hasAfterSale)) {
            return "VIEW";
        }
        return "DISABLED";
    }
}
