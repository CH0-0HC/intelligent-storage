package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("inventory")
public class Inventory {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long productId;
    
    private Integer quantity;
    
    private Integer warningQuantity;
    
    private LocalDateTime lastInTime;
    
    private LocalDateTime lastOutTime;
    
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
}
