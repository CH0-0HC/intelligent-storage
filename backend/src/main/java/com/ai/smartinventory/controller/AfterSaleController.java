package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.ai.smartinventory.common.AfterSaleRule;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.dto.AfterSaleApplyDTO;
import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.dto.AfterSaleHandleDTO;
import com.ai.smartinventory.entity.AfterSale;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.service.AfterSaleService;
import com.ai.smartinventory.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 售后（退款/退货/换货）接口。
 *
 * 【本控制器解决的问题】
 * 系统此前没有任何售后接口，用户确认收货（order_status = 3）后页面上
 * 不存在申请售后入口。本控制器补齐「申请 → 查看 → 撤销」与商家侧
 * 「审核 → 完成」的完整闭环。
 *
 * 【权限约定】
 * · 买家侧接口（申请 / 我的列表 / 详情 / 撤销）必须登录，且只能操作自己的订单/售后单；
 * · 商家侧接口（列表 / 处理）用 @SaCheckRole(mode = SaMode.OR) ——
 *   **多角色注解必须显式写 OR**：mode 默认是 AND，而 sys_user.role 是单值，
 *   漏写会让接口对所有角色永久 403（本项目已在 OrderController#shipOrder 踩过）。
 * · 商家数据范围由 AfterSaleService 内部经 order → order_item → product.merchant_id 收敛。
 */
@Slf4j
@RestController
@RequestMapping("/after-sale")
@Tag(name = "售后服务", description = "确认收货后的退款/退货/换货申请与处理")
public class AfterSaleController {

    @Resource
    private AfterSaleService afterSaleService;

    @Resource
    private OrderService orderService;

    // ==================== 公共 / 买家 ====================

    @Operation(summary = "售后原因字典", description = "返回原因编码与展示文案，供前端下拉使用（避免前端硬编码）")
    @GetMapping("/reasons")
    public Result<Map<String, String>> reasons(
            // 【2026-10-10】按界面语言返回字典。缺省 zh —— 既有验证脚本
            // （verify-after-sale.mjs）不传 lang，行为与改动前逐字一致。
            @RequestParam(name = "lang", defaultValue = "zh") String lang) {
        return Result.success(AfterSaleRule.reasonCatalog(lang));
    }

    @Operation(summary = "查询订单的售后可申请状态",
            description = "返回入口是否可见/可点击、剩余时效、已有售后单等信息。"
                    + "订单列表页也会在 Order.afterSale 中回填同一结构，日常渲染无需调用本接口。")
    @GetMapping("/eligibility/{orderId}")
    public Result<AfterSaleEligibility> eligibility(@PathVariable Long orderId) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        String role = currentRole();
        Order order = orderService.getOrderByIdWithPermission(orderId, userId, role);
        if (order == null) {
            return Result.forbidden("订单不存在或无权访问");
        }
        return Result.success(afterSaleService.evaluateForBuyer(order, null));
    }

    @Operation(summary = "提交售后申请",
            description = "仅「已完成（确认收货）」且在 15 天申请期内的订单可提交；"
                    + "同一订单同一时刻只允许一个进行中的售后单；"
                    + "「7天无理由」原因仅在确认收货后 7 天内可选。")
    // 【P1-1 限流补全】售后会触发退款/回补库存，限流防刷。
    @RateLimit(key = "after_sale:apply", permitsPerMinute = 20, permitsPerHour = 100)
    @PostMapping
    public Result<AfterSale> apply(@RequestBody AfterSaleApplyDTO dto) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        AfterSale afterSale = afterSaleService.apply(userId, dto);
        return Result.success("售后申请已提交，请等待商家处理", afterSale);
    }

    @Operation(summary = "我的售后列表")
    @GetMapping("/list")
    public Result<PageResult<AfterSale>> myList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(afterSaleService.getUserPage(userId, page, size, status));
    }

    @Operation(summary = "售后详情", description = "买家仅可查看本人售后单；商家仅可查看含自家商品的订单售后单")
    @GetMapping("/{id}")
    public Result<AfterSale> detail(@PathVariable Long id) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        AfterSale afterSale = afterSaleService.getByIdWithPermission(id, userId, currentRole());
        if (afterSale == null) {
            return Result.forbidden("售后单不存在或无权访问");
        }
        return Result.success(afterSale);
    }

    @Operation(summary = "撤销售后申请", description = "仅「待处理」状态可撤销；撤销后剩余时效内可重新申请")
    // 【P1-1 限流补全】撤销可配合重新申请刷库存回补，限流收紧。
    @RateLimit(key = "after_sale:cancel", permitsPerMinute = 10, permitsPerHour = 50)
    @PutMapping("/cancel/{id}")
    public Result<AfterSale> cancel(@PathVariable Long id) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success("已撤销售后申请", afterSaleService.cancelByBuyer(id, userId));
    }

    // ==================== 商家 / 管理员 ====================

    @Operation(summary = "售后处理列表（商家/管理员）")
    // mode = SaMode.OR 必须显式声明，详见类注释
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    @GetMapping("/manage/list")
    public Result<PageResult<AfterSale>> manageList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status) {
        return Result.success(afterSaleService.getManagePage(page, size, status));
    }

    @Operation(summary = "处理售后（商家/管理员）",
            description = "action: agree-同意（仅退款直接完成，退货退款/换货进入处理中） / "
                    + "reject-拒绝（需填原因） / finish-确认完成（退货收到后）")
    @SaCheckRole(value = {"admin", "merchant"}, mode = SaMode.OR)
    // 【P1-1 限流补全】处理售后（可能触发退款/回补库存），限流防刷。
    @RateLimit(key = "after_sale:handle", permitsPerMinute = 30, permitsPerHour = 200)
    @PutMapping("/manage/handle/{id}")
    public Result<AfterSale> handle(@PathVariable Long id, @RequestBody AfterSaleHandleDTO dto) {
        String role = currentRole();
        Long operatorId = StpUtil.getLoginIdAsLong();
        return Result.success("处理成功", afterSaleService.handle(id, role, operatorId, dto));
    }

    // ==================== 工具 ====================

    private String currentRole() {
        String role = MerchantContext.currentRole();
        return role == null ? MerchantContext.ROLE_USER : role;
    }
}
