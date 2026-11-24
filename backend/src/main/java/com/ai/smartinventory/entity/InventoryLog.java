package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("inventory_log")
public class InventoryLog {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long productId;
    
    private Integer type;
    
    private Integer quantity;
    
    private Integer beforeQuantity;
    
    private Integer afterQuantity;
    
    private String orderType;
    
    private Long orderId;
    
    private String remark;
    
    private Long operatorId;
    
    @TableLogic
    private Integer deleted;
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    
    @TableField(exist = false)
    private String productName;
}
