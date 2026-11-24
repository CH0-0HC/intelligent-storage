import request from '@/utils/request'
import { currentLang } from '@/i18n'

/**
 * 客服对接（超期售后申诉工单）接口封装。
 *
 * 【这条链路解决什么问题】
 * 售后时效改造前是全站写死的 15 天：超期即彻底不可申请，买家没有任何
 * 「还能不能争取一下」的入口，客服在系统里也无处受理。
 * 现在退货期限由商家按商品设置（product.return_window_days），
 * 买家在**超出该期限**后先提客服工单 → 客服依据「商家为该商品指定的统一标准」
 * 裁定 → 通过后买家获得**一次性、7 天有效**的放行授权，据此才能提交售后申请。
 *
 * 【与 afterSale.js 的分工】
 * · 工单（service_ticket）只表达「我要申诉，请客服裁定」，**不动钱、不动库存**，
 *   因此允许被驳回后重新提交；
 * · 售后单（after_sale）才是退款/退货/换货的履约闭环，一旦提交即占用库存与资金。
 * 两者唯一的交点：客服放行后，售后申请会带上该工单 id 并把授权标记为已消费。
 *
 * 【权限约定（与后端 ServiceTicketController 逐条对齐）】
 * · 买家侧（meta 之外的 check / 提交 / 我的列表 / 详情 / 撤销）必须登录，
 *   且只能操作自己的订单与工单；
 * · 客服工作台（manage/list、manage/resolve）只对 kefu / admin 开放；
 *   **merchant 刻意没有裁定权** —— 商家是被申请方，自己裁定自己中立性不成立。
 */

/**
 * 客服裁定标准与退货期限字典（公共接口，无需特定角色）。
 *
 * 返回 { presetReturnWindowDays, defaultReturnWindowDays, maxReturnWindowDays,
 *       noReasonDays, csAuthorizationDays, reasons: {code: 文案}, csStandard }。
 *
 * 【为什么裁定标准要从后端下发】
 * csStandard 是「客服看到的裁定依据」与「买家申诉页看到的规则」的**同一段原文**。
 * 一旦前端各自硬编码，就会出现「客服按 A 标准驳回、买家以为按 B 标准」的争议。
 */
export function getServiceTicketMeta() {
  return request({
    url: '/service-ticket/meta',
    method: 'get',
    // 字典与「客服裁定标准」原文都由后端下发，按当前界面语言取
    params: { lang: currentLang() }
  })
}

/**
 * 查询某订单/商品能否联系客服（买家）。
 *
 * 返回 { allowed, hint, orderId, orderItemId, productId, productName, orderNo,
 *       completeTime, returnWindowDays, returnWindowText, daysOverdue,
 *       existingTicketId, existingTicketNo, items: [...] }。
 *
 * 【关键约定】allowed === false 时 hint 是**可直接展示**的原因文案（后端已写好口径），
 * 前端不要自己拼「为什么不行」；传 orderItemId / productId 时按该商品算期限，
 * 不传则按整单**最严一档**算。提交前与页面上必须用同一个接口，避免「页面说能提、提交却被拒」。
 */
export function checkServiceTicket(params) {
  return request({
    url: '/service-ticket/check',
    method: 'get',
    params
  })
}

/**
 * 提交客服工单（买家）。
 *
 * @param {{orderId: number, orderItemId?: number, productId?: number,
 *          reasonCode: string, description?: string, images?: string[]}} data
 * @returns data 为 ServiceTicket
 *
 * 注意：工单不含退款金额与售后类型 —— 那些要等客服放行后，在正式售后申请里再填。
 */
export function submitServiceTicket(data) {
  return request({
    url: '/service-ticket',
    method: 'post',
    data
  })
}

/**
 * 我的客服工单列表（买家）。
 *
 * @param {{page?: number, size?: number, status?: number}} params
 *        status：0-待客服裁定 / 1-已裁定 / 2-已取消；不传表示全部
 */
export function getMyServiceTickets(params) {
  return request({
    url: '/service-ticket/list',
    method: 'get',
    params
  })
}

/**
 * 客服工单详情。
 *
 * 买家仅可查看本人工单；客服/管理员可查看全部（否则无法裁定）。
 * 「我的客服记录」页的查看弹窗与客服裁定页共用本接口。
 */
export function getServiceTicketDetail(id) {
  return request({
    url: `/service-ticket/${id}`,
    method: 'get'
  })
}

/** 撤销客服工单（买家，仅「待客服裁定」状态可撤销） */
export function cancelServiceTicket(id) {
  return request({
    url: `/service-ticket/cancel/${id}`,
    method: 'put'
  })
}

/**
 * 客服工单列表（客服 / 管理员工作台）。
 *
 * @param {{page?: number, size?: number, status?: number, keyword?: string}} params
 *        keyword 命中订单号或工单号
 */
export function getManageServiceTickets(params) {
  return request({
    url: '/service-ticket/manage/list',
    method: 'get',
    params
  })
}

/**
 * 裁定客服工单（客服 / 管理员）。
 *
 * @param {number} id 工单 ID
 * @param {{resolution: 1|2, remark?: string}} data
 *        resolution：1-允许继续申请售后（一次性放行，7 天内有效） / 2-驳回
 *        remark：裁定说明，**驳回时必填**（否则后端 400），最多 500 字
 */
export function resolveServiceTicket(id, data) {
  return request({
    url: `/service-ticket/manage/resolve/${id}`,
    method: 'put',
    data
  })
}
