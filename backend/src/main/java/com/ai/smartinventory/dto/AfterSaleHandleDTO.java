package com.ai.smartinventory.dto;

import lombok.Data;

/**
 * 商家处理售后入参。
 */
@Data
public class AfterSaleHandleDTO {

    /** 处理动作：agree-同意 / reject-拒绝 / finish-确认完成（退货已收到） */
    private String action;

    /** 处理备注。action=reject 时必填，其余可选 */
    private String remark;
}
