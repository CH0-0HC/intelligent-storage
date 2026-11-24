package com.ai.smartinventory.dto;

import com.ai.smartinventory.service.OrderService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 下单入参。
 *
 * 【安全修复·P0-1】这里**故意不再有** totalAmount / discountAmount / payAmount，
 * 也不再接收商品单价 / 名称 / 图片。
 * 金额的唯一权威来源是 product 表：客户端只能决定「买什么商品、买几件」，
 * 单价、小计、订单总额一律由服务端按 productId 回查后计算。
 * 之前这些字段直接透传入库，攻击者传 price=0.01 就能「1 分钱下单」。
 *
 * 【测试报告#2·消除同名遮蔽】本类原先没有 items 字段，被 OrderController 里一个
 * 同名 static 内部类遮蔽，**真正生效的是那个内部类**，本文件一直是死代码
 * （在上面加校验也不会生效）。现已合并：删掉内部类、本类补齐 items，
 * OrderController 改为 import 本类并加 @Valid —— 校验只此一处，不会再有第二份入参定义。
 */
@Data
public class OrderCreateDTO {

    // 【测试报告#2】收货三件套 + 明细此前全靠 Controller 手写 if 判空，
    // 电话完全没校验：shippingPhone 填 "abc" 能直接下单入库并在商家订单列表展示。
    // 消息与原手写校验逐字一致，前端提示文案不会变。
    @NotBlank(message = "请输入收货人姓名")
    @Size(max = 32, message = "收货人姓名长度不能超过32位")
    private String shippingName;

    @NotBlank(message = "请输入收货人电话")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货人电话格式不正确")
    private String shippingPhone;

    @NotBlank(message = "请输入收货地址")
    @Size(max = 200, message = "收货地址长度不能超过200位")
    private String shippingAddress;

    @Size(max = 200, message = "备注长度不能超过200位")
    private String remark;

    // 对应原 OrderController.OrderCreateDTO.items，类型保持为 OrderService 的 record 不变
    @NotEmpty(message = "请至少选择一个商品")
    private List<OrderService.OrderItemDTO> items;
}
