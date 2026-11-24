/**
 * 售后状态 / 类型的展示字典（前端唯一来源）。
 *
 * 状态码与后端 AfterSaleRule.STATUS_* 一一对应：
 *   0-待处理 1-商家已同意 2-已完成 3-商家已拒绝 4-买家已撤销
 * 类型码与 AfterSaleRule.TYPE_* 对应：1-仅退款 2-退货退款 3-换货
 *
 * 这里只放「状态码 → i18n key / 标签颜色」，不放业务规则：
 * 「能不能申请售后」由后端 AfterSaleRule 判定并通过 order.afterSale 下发，
 * 前端不重复实现时效计算，从根上避免两边口径不一致。
 */

export const AFTER_SALE_STATUS_MAP = {
  0: { key: 'after_sale.status_pending', type: 'warning' },
  1: { key: 'after_sale.status_agreed', type: 'primary' },
  2: { key: 'after_sale.status_finished', type: 'success' },
  3: { key: 'after_sale.status_rejected', type: 'danger' },
  4: { key: 'after_sale.status_cancelled', type: 'info' }
}

export const AFTER_SALE_TYPE_MAP = {
  1: { key: 'after_sale.type_refund_only' },
  2: { key: 'after_sale.type_return_refund' },
  3: { key: 'after_sale.type_exchange' }
}

/** 状态码归一化：后端可能把 TINYINT 序列化成字符串，统一转数字再比较 */
export const normalizeAfterSaleStatus = (status) => Number(status)

/** 进行中（待处理 / 商家已同意）—— 会占用「一单一售后」额度 */
export const isActiveAfterSale = (status) => {
  const s = normalizeAfterSaleStatus(status)
  return s === 0 || s === 1
}

export const afterSaleStatusType = (status) =>
  AFTER_SALE_STATUS_MAP[normalizeAfterSaleStatus(status)]?.type || 'info'

export const afterSaleStatusKey = (status) =>
  AFTER_SALE_STATUS_MAP[normalizeAfterSaleStatus(status)]?.key || 'after_sale.status_unknown'

export const afterSaleTypeKey = (type) =>
  AFTER_SALE_TYPE_MAP[Number(type)]?.key || 'after_sale.type_refund_only'
