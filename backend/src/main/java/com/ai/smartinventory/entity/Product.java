package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    
    private Long categoryId;
    
    private Long merchantId;
    
    private BigDecimal price;
    
    private BigDecimal cost;
    
    private Integer stock;
    
    private String unit;
    
    private String image;
    
    private String images;
    
    private String description;
    
    private String specs;
    
    private String tags;
    
    private Integer sales;
    
    private BigDecimal rating;
    
    private Integer status;
    
    private Integer isHot;
    
    private Integer isRecommend;

    /**
     * 【2026-09-28 新增】该商品支持的退货退款期限（天）。
     *
     * <p>商家在上架/编辑商品时设置，取值 7 / 15 / 30 或自定义（1~365）。
     * <ul>
     *   <li>{@code 0} —— 该商品不支持退货退款（仅「质量问题」等有理由售后
     *       仍可通过客服裁定通道处理）；</li>
     *   <li>{@code null}（历史数据 / 老前端未提交）—— 按 {@link com.ai.smartinventory.common.AfterSaleRule#DEFAULT_RETURN_WINDOW_DAYS}
     *       处理，即沿用改造前全站统一的 15 天，保证存量行为不变。</li>
     * </ul>
     */
    private Integer returnWindowDays;

    /**
     * 【2026-09-28 新增】是否支持「无理由」退货。
     *
     * <p>商家设了期限但不愿承担无理由退货成本时可置 0：此时用户在期限内
     * 仍可因质量问题 / 描述不符 / 破损等**有理由**原因申请，
     * 但原因下拉里的「无理由」项会被禁用。
     * {@code null} 视为支持（与改造前一致）。
     */
    private Integer returnNoReason;
    
    @TableLogic
    private Integer deleted;
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    
    @TableField(exist = false)
    private String categoryName;
}
