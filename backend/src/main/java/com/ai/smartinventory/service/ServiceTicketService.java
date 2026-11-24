package com.ai.smartinventory.service;

import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.ServiceTicketApplyDTO;
import com.ai.smartinventory.dto.ServiceTicketHandleDTO;
import com.ai.smartinventory.entity.ServiceTicket;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 客服裁定工单服务 —— 超出商品退货期限后的唯一放行通道。
 *
 * <p>规则口径统一在 {@link com.ai.smartinventory.common.AfterSaleRule}，
 * 本接口只负责取数、落库与权限边界（与 AfterSaleService 同一分工）。
 */
public interface ServiceTicketService extends IService<ServiceTicket> {

    /**
     * 买家能否就该订单（可选指定商品）提交客服工单。
     *
     * <p>返回结构为可直接展示的提示：{@code {allowed, hint, ...}}。
     * 把判定放在服务端是为了避免「按钮能点但提交被拒」。
     */
    Map<String, Object> checkSubmittable(Long userId, Long orderId, Long orderItemId, Long productId);

    /** 买家提交工单。校验不通过抛 BusinessException(400, 可读原因)。 */
    ServiceTicket submit(Long userId, ServiceTicketApplyDTO dto);

    /** 我的工单列表（买家视角） */
    PageResult<ServiceTicket> getUserPage(Long userId, Integer page, Integer size, Integer status);

    /** 客服工作台列表（客服/管理员；可按状态、订单号筛） */
    PageResult<ServiceTicket> getManagePage(Integer page, Integer size, Integer status, String keyword);

    /**
     * 客服裁定。仅 kefu / admin 可调用。
     *
     * @param operatorId 裁定客服 ID
     */
    ServiceTicket resolve(Long id, Long operatorId, ServiceTicketHandleDTO dto);

    /** 买家撤销自己「待裁定」的工单 */
    ServiceTicket cancelByBuyer(Long id, Long userId);

    /** 工单详情（买家仅本人；客服/管理员全局） */
    ServiceTicket getByIdWithPermission(Long id, Long userId, String role);

    /**
     * 取某订单当前**可用**的放行授权（已裁定通过 + 未消费 + 未过期）。
     *
     * <p>供售后资格判定（AfterSaleRule.evaluate）与售后提交共用，
     * 保证「入口显示可申请」与「提交被放行」是同一个口径。
     */
    ServiceTicket findUsableAuthorization(Long orderId);

    /**
     * 批量版本：订单列表一次渲染 N 个订单，逐单查库会 N+1。
     *
     * @return {@code orderId → 可用授权工单}
     */
    Map<Long, ServiceTicket> findUsableAuthorizations(List<Long> orderIds);

    /** 把工单标记为「授权已使用」（售后提交成功时调用，保证一张工单只放行一次） */
    boolean consumeAuthorization(Long ticketId);
}
