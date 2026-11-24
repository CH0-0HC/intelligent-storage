package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.ai.smartinventory.common.AfterSaleRule;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.dto.ServiceTicketApplyDTO;
import com.ai.smartinventory.dto.ServiceTicketHandleDTO;
import com.ai.smartinventory.entity.ServiceTicket;
import com.ai.smartinventory.service.ServiceTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客服对接接口 —— 「超期售后先联系客服」这条链路的全部出入口。
 *
 * 【解决的问题】
 * 售后时效改造前全站写死 15 天，超期后用户在所有界面只能看到置灰按钮，
 * 既没有申诉入口，客服在系统里也无处受理。现在：
 *   超期 → 提交工单 → 客服依商家统一标准裁定 → 通过则继续申请售后。
 *
 * 【权限约定】
 * · 买家侧（可提交性 / 提交 / 我的列表 / 详情 / 撤销）必须登录，且只能操作自己的订单与工单；
 * · 客服工作台（列表 / 裁定）允许 {@code kefu} 与 {@code admin}。
 *   **必须显式写 mode = SaMode.OR** —— 该属性默认是 AND，而 sys_user.role 是单值，
 *   漏写会让接口对所有角色永久 403（本项目已在 OrderController#shipOrder 踩过）。
 * · 商家（merchant）**刻意不给裁定权**：商家是被申请方，自己裁定自己是否该退货
 *   中立性不成立；商家侧仍通过「售后处理」页面参与，但超期放行由平台客服裁定。
 */
@Slf4j
@RestController
@RequestMapping("/service-ticket")
@Tag(name = "客服对接", description = "超出售后期限后的客服裁定与放行")
public class ServiceTicketController {

    @Resource
    private ServiceTicketService serviceTicketService;

    // ==================== 公共 / 买家 ====================

    @Operation(summary = "客服裁定标准与退货期限字典",
            description = "返回预设退货期限档位、上限、以及客服裁定的统一标准口径，供前端展示与下拉使用")
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta(
            // 【2026-10-10】按界面语言下发。缺省 zh：scripts/verify-return-rule-and-buy-now.mjs
            // 断言「客服裁定统一标准由服务端下发」且长度 > 50，不传 lang 时行为与改动前一致。
            @RequestParam(name = "lang", defaultValue = "zh") String lang) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("presetReturnWindowDays", AfterSaleRule.PRESET_RETURN_WINDOW_DAYS);
        out.put("defaultReturnWindowDays", AfterSaleRule.DEFAULT_RETURN_WINDOW_DAYS);
        out.put("maxReturnWindowDays", AfterSaleRule.MAX_RETURN_WINDOW_DAYS);
        out.put("noReasonDays", AfterSaleRule.NO_REASON_DAYS);
        out.put("csAuthorizationDays", AfterSaleRule.CS_AUTHORIZATION_DAYS);
        out.put("reasons", AfterSaleRule.reasonCatalog(lang));
        // 裁定标准原文：客服界面与买家申诉页都展示同一段，避免口径被各端改写
        out.put("csStandard", buildCsStandard(AfterSaleRule.isEnglish(lang)));
        return Result.success(out);
    }

    @Operation(summary = "查询某订单/商品能否联系客服",
            description = "返回 allowed 与可直接展示的 hint；前端据此决定「联系客服」按钮是否可点")
    @GetMapping("/check")
    public Result<Map<String, Object>> check(@RequestParam Long orderId,
                                             @RequestParam(required = false) Long orderItemId,
                                             @RequestParam(required = false) Long productId) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(serviceTicketService.checkSubmittable(userId, orderId, orderItemId, productId));
    }

    @Operation(summary = "提交客服工单", description = "仅「已完成且已付款、且已超出商品退货期限」的订单可提交")
    // 工单不涉及退款，但会被反复提交，故限流比售后申请略宽松、仍设上下界。
    @RateLimit(key = "service_ticket:submit", permitsPerMinute = 10, permitsPerHour = 60)
    @PostMapping
    public Result<ServiceTicket> submit(@RequestBody ServiceTicketApplyDTO dto) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        ServiceTicket ticket = serviceTicketService.submit(userId, dto);
        return Result.success("客服工单已提交，请等待客服裁定", ticket);
    }

    @Operation(summary = "我的客服工单列表")
    @GetMapping("/list")
    public Result<PageResult<ServiceTicket>> myList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(serviceTicketService.getUserPage(userId, page, size, status));
    }

    @Operation(summary = "客服工单详情",
            description = "买家仅可查看本人工单；客服/管理员可查看全部（以便裁定）")
    @GetMapping("/{id}")
    public Result<ServiceTicket> detail(@PathVariable Long id) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        String role = currentRole();
        ServiceTicket ticket = serviceTicketService.getByIdWithPermission(id, userId, role);
        if (ticket == null) {
            return Result.forbidden("客服工单不存在或无权访问");
        }
        return Result.success(ticket);
    }

    @Operation(summary = "撤销客服工单", description = "仅「待客服裁定」状态可撤销")
    @RateLimit(key = "service_ticket:cancel", permitsPerMinute = 10, permitsPerHour = 50)
    @PutMapping("/cancel/{id}")
    public Result<ServiceTicket> cancel(@PathVariable Long id) {
        if (!StpUtil.isLogin()) {
            return Result.unauthorized("请先登录");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success("已撤销客服工单", serviceTicketService.cancelByBuyer(id, userId));
    }

    // ==================== 客服 / 管理员 ====================

    @Operation(summary = "客服工单列表（客服/管理员工作台）")
    @SaCheckRole(value = {"kefu", "admin"}, mode = SaMode.OR)
    @GetMapping("/manage/list")
    public Result<PageResult<ServiceTicket>> manageList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(serviceTicketService.getManagePage(page, size, status, keyword));
    }

    @Operation(summary = "裁定客服工单（客服/管理员）",
            description = "resolution: 1-允许继续申请售后（一次性放行授权，7 天内有效） / 2-驳回（须填依据）")
    @SaCheckRole(value = {"kefu", "admin"}, mode = SaMode.OR)
    @RateLimit(key = "service_ticket:resolve", permitsPerMinute = 60, permitsPerHour = 600)
    @PutMapping("/manage/resolve/{id}")
    public Result<ServiceTicket> resolve(@PathVariable Long id, @RequestBody ServiceTicketHandleDTO dto) {
        Long operatorId = StpUtil.getLoginIdAsLong();
        return Result.success("裁定完成", serviceTicketService.resolve(id, operatorId, dto));
    }

    // ==================== 工具 ====================

    private String currentRole() {
        String role = MerchantContext.currentRole();
        return role == null ? MerchantContext.ROLE_USER : role;
    }

    /**
     * 客服裁定的**统一标准**原文。
     *
     * <p>放在服务端下发而不是前端硬编码，是为了让「客服看到的裁定依据」与
     * 「买家在申诉页看到的规则」逐字一致 —— 一旦口径分叉，就会出现
     * 「客服按 A 标准驳回、买家以为按 B 标准」的争议。
     */
    private String buildCsStandard() {
        return buildCsStandard(false);
    }

    /**
     * 客服裁定标准原文（中 / 英）。
     *
     * <p>放在服务端下发而不是前端硬编码，是为了让「客服看到的裁定依据」与
     * 「买家在申诉页看到的规则」逐字一致 —— 一旦口径分叉，就会出现
     * 「客服按 A 标准驳回、买家以为按 B 标准」的争议。
     *
     * <p>2026-10-10 补英文：这段是**唯一**一段纯中文长文，切到 English 界面后
     * 客服工作台与申诉页会显示一整段中文，与其余已翻译文案割裂。
     * 英文版与中文版必须逐条对齐（都是 5 条），改一条要同时改另一条。
     *
     * @param english true 返回英文，false 返回中文
     */
    private String buildCsStandard(boolean english) {
        if (english) {
            return "Requests past the return window are reviewed by customer service against "
                    + "the return policy the merchant set for the product:\n"
                    + "1. If the product developed a quality issue, differs from the description, "
                    + "was damaged or arrived incomplete within the return window (including the "
                    + "merchant's no-reason period), and the buyer provided valid evidence — approve, "
                    + "so the buyer may continue with a return request;\n"
                    + "2. If it is only a personal preference (dislike, no longer wanted) and the "
                    + "merchant's no-reason period has passed — reject;\n"
                    + "3. For products the merchant explicitly marked as \"no returns\", do not pass "
                    + "them through unless there is a quality issue — reject;\n"
                    + "4. If the evidence is insufficient or the situation cannot be verified, tell "
                    + "the buyer once what still needs to be provided, then decide;\n"
                    + "5. Once approved, the buyer must submit the return request within "
                    + AfterSaleRule.CS_AUTHORIZATION_DAYS
                    + " days; afterwards they must contact customer service again.";
        }
        return "超出售后期限的申请，由客服依据「商家为该商品指定的退货退款规则」统一裁定：\n"
                + "1. 商品在退货期限内（含商家设置的无理由退货期）出现质量问题、"
                + "与描述不符、破损少件等情形，且买家提供了有效凭证 —— 同意继续申请售后；\n"
                + "2. 仅因个人原因（不喜欢、不想要等无理由诉求）且已超出商家设置的无理由退货期 —— 驳回；\n"
                + "3. 商家已明确标注「不支持退货退款」的商品，除质量问题外不予放行 —— 驳回；\n"
                + "4. 凭证不足或情形无法核实的，应一次性告知买家需补充的材料，再行裁定；\n"
                + "5. 同意后买家须在 " + AfterSaleRule.CS_AUTHORIZATION_DAYS
                + " 天内提交售后申请，逾期需重新联系客服。";
    }
}
