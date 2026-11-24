import request from '@/utils/request'
import { currentLang } from '@/i18n'

/**
 * 售后（退款 / 退货 / 换货）接口封装。
 *
 * 【与订单接口的分工】
 * · 订单侧只负责「确认收货」把订单推进到已完成；
 * · 售后侧负责已完成之后的退款/退货/换货闭环。
 * 「能不能申请售后」一律由后端判定（订单列表的 order.afterSale 字段），
 * 前端不自行拼时效规则，避免出现「按钮能点但提交被拒」。
 */

/** 提交售后申请 */
export function applyAfterSale(data) {
  return request({
    url: '/after-sale',
    method: 'post',
    data
  })
}

/** 我的售后列表（买家） */
export function getAfterSaleList(params) {
  return request({
    url: '/after-sale/list',
    method: 'get',
    params
  })
}

/** 售后详情 */
export function getAfterSaleDetail(id) {
  return request({
    url: `/after-sale/${id}`,
    method: 'get'
  })
}

/** 撤销售后申请（仅「待处理」可撤销） */
export function cancelAfterSale(id) {
  return request({
    url: `/after-sale/cancel/${id}`,
    method: 'put'
  })
}

/**
 * 单订单售后资格查询。
 * 订单列表已由后端回填 order.afterSale，这里主要给「售后申请页」
 * 直接进入 / 刷新时重新确认状态使用。
 */
export function getOrderEligibility(orderId) {
  return request({
    url: `/after-sale/eligibility/${orderId}`,
    method: 'get'
  })
}

/** 售后原因字典（编码 → 文案，避免前端硬编码） */
export function getAfterSaleReasons() {
  return request({
    url: '/after-sale/reasons',
    method: 'get',
    params: { lang: currentLang() }
  })
}

/** 售后处理列表（商家 / 管理员） */
export function getManageAfterSaleList(params) {
  return request({
    url: '/after-sale/manage/list',
    method: 'get',
    params
  })
}

/**
 * 处理售后（商家 / 管理员）
 * @param {number} id 售后单 ID
 * @param {{action: 'agree'|'reject'|'finish', remark?: string}} data
 */
export function handleAfterSale(id, data) {
  return request({
    url: `/after-sale/manage/handle/${id}`,
    method: 'put',
    data
  })
}
