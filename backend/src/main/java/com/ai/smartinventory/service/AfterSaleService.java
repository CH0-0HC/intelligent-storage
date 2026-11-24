package com.ai.smartinventory.service;

import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.AfterSaleApplyDTO;
import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.dto.AfterSaleHandleDTO;
import com.ai.smartinventory.entity.AfterSale;
import com.ai.smartinventory.entity.Order;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 售后申请服务。
 *
 * 规则判定统一收敛在 {@link com.ai.smartinventory.common.AfterSaleRule}，
 * 本接口只负责取数、落库与权限边界。
 */
public interface AfterSaleService extends IService<AfterSale> {

    /**
     * 判定单个订单当前能否申请售后（买家自查 / 申请前置校验共用）。
     *
     * @param order  订单；为 null 时返回不可申请
     * @param userId 当前登录用户，用于核验订单归属；不归属时抛 403
     */
    AfterSaleEligibility evaluateForBuyer(Order order, Long userId);

    /**
     * 批量判定。订单列表一次渲染 N 个订单，逐单查库会形成 N+1；
     * 这里一次性把售后单捞回来内存分组。
     *
     * @param orders 订单列表（可为空）
     */
    Map<Long, AfterSaleEligibility> evaluateBatch(List<Order> orders);

    /**
     * 买家提交售后申请。所有规则校验在此统一执行，校验不过抛 BusinessException(400, 可读原因)。
     */
    AfterSale apply(Long userId, AfterSaleApplyDTO dto);

    /** 我的售后列表（买家视角） */
    PageResult<AfterSale> getUserPage(Long userId, Integer page, Integer size, Integer status);

    /**
     * 售后详情。权限：买家仅本人；商家仅含自家商品的订单；管理员全局。
     * 无权访问返回 null（控制器统一转 403），不区分「不存在」与「属于别人」。
     */
    AfterSale getByIdWithPermission(Long id, Long userId, String role);

    /** 买家撤销申请。仅「待处理」状态可撤销。 */
    AfterSale cancelByBuyer(Long id, Long userId);

    /** 商家/管理员售后列表（商家范围经 order → order_item → product.merchant_id 收敛） */
    PageResult<AfterSale> getManagePage(Integer page, Integer size, Integer status);

    /** 商家/管理员处理售后：agree / reject / finish */
    AfterSale handle(Long id, String role, Long operatorId, AfterSaleHandleDTO dto);
}
