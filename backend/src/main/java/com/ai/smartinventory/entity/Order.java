package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("`order`")
public class Order {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String orderNo;
    
    private Long userId;
    
    private BigDecimal totalAmount;
    
    private BigDecimal discountAmount;
    
    private BigDecimal payAmount;
    
    private Integer payStatus;
    
    private LocalDateTime payTime;
    
    private Integer orderStatus;
    
    private String shippingName;
    
    private String shippingPhone;
    
    private String shippingAddress;
    
    private String remark;
    
    private LocalDateTime cancelTime;

    /**
     * 取消原因：AUTO_TIMEOUT（超时未付款由系统自动取消）/ USER（用户主动取消）/
     * MERCHANT（商家关闭）。
     *
     * 【为什么必须落库】订单状态 4「已取消」同时覆盖「用户自己点的取消」与
     * 「15 分钟没付款被系统取消」两种语义完全不同的场景。前端若要给出
     * 「因超时未付款，订单已自动取消」这类提示，就必须有区分依据 ——
     * 只凭 orderStatus=4 + payStatus=0 是分辨不出来的（用户手动取消的未付款单
     * 也长这样），会把「你自己取消的」错误地提示成「系统帮你取消了」。
     */
    private String cancelReason;
    
    private LocalDateTime completeTime;
    
    /** 物流公司 */
    private String logisticsCompany;
    
    /** 物流单号 */
    private String trackingNo;
    
    /** 发货时间 */
    private LocalDateTime shipTime;
    
    @TableLogic
    private Integer deleted;
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    
    @TableField(exist = false)
    private List<OrderItem> items;
    
    @TableField(exist = false)
    private String username;

    /**
     * 付款截止时间（= createTime + 配置的未付款超时分钟数），**仅对
     * 「待付款(0) + 未付款(0)」的订单回填**，其余订单为 null。
     *
     * 【为什么由后端算】前端要用它渲染「剩余 12:34 自动取消」的倒计时。
     * 若前端自己拿 createTime + 15 分钟，就把超时时长硬编码进了两个代码库：
     * 后端改了 `order.timeout-cancel.unpaid-timeout-minutes`，前端倒计时仍按
     * 15 分钟走 —— 用户会看到「还剩 2 分钟」的单子突然消失。这里由
     * OrderServiceImpl 用同一份配置回填，前端只做展示、不做推导。
     */
    @TableField(exist = false)
    private LocalDateTime payDeadline;

    /**
     * 该订单的售后可申请状态（含入口是否可见、剩余时效、已有售后单 ID）。
     *
     * 【为什么挂在订单上】「我的订单」一次渲染 N 个订单，若前端逐单再发一次
     * 资格查询，就是 N+1 次请求。这里由 OrderServiceImpl 在分页查询后
     * **一次批量判定**回填，前端单请求即拿到完整入口状态。
     * 判定口径统一在 common/AfterSaleRule，前端只消费、不推导。
     */
    @TableField(exist = false)
    private com.ai.smartinventory.dto.AfterSaleEligibility afterSale;
}
