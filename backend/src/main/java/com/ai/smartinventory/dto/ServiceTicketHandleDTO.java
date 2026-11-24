package com.ai.smartinventory.dto;

import lombok.Data;

/**
 * 客服裁定入参。
 *
 * <p>裁定标准是**商家为该商品指定的统一标准**（{@code product.return_window_days}
 * 及其「是否支持无理由」），客服只在超期场景下依据该标准判断是否放行；
 * {@code remark} 是裁定依据的书面留痕，双方均可见。
 */
@Data
public class ServiceTicketHandleDTO {

    /**
     * 裁定结果：
     * <ul>
     *   <li>{@code 1} —— 允许继续申请售后（用户获得一次性放行授权，7 天内须提交）；</li>
     *   <li>{@code 2} —— 驳回申请。</li>
     * </ul>
     */
    private Integer resolution;

    /** 裁定说明。驳回时必填（要让买家知道依据哪条规则被拒） */
    private String remark;
}
