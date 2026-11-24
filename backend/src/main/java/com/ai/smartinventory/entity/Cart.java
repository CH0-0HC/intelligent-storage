package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 购物车实体
 */
@Data
@TableName("cart")
public class Cart {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long productId;

    private Integer quantity;

    /**
     * 是否勾选参与结算：1-选中，0-未选中。
     *
     * 【2026-09-22 新增】此前购物车没有这个字段，前端也就无法提供「勾选要买的商品」，
     * 结算必然是全量下单。放在服务端而不是前端内存里，是为了满足
     * 「刷新页面 / 重新登录后勾选状态仍保持」。
     * 注意：CartMapper.selectByUserIdWithProduct 用的是 SELECT c.*，
     * 所以这里加了字段、库里有列之后，列表接口会自动带出该字段（无需改 SQL）。
     */
    private Integer selected;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String productName;

    @TableField(exist = false)
    private String productImage;

    @TableField(exist = false)
    private java.math.BigDecimal productPrice;

    @TableField(exist = false)
    private Integer productStock;
}