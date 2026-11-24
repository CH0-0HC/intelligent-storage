import { describe, it, expect } from 'vitest'
import {
  ORDER_STATUS_MAP,
  PURCHASE_STATUS_MAP,
  getOrderStatusText,
  getOrderStatusType,
  getPurchaseStatusText,
  getPurchaseStatusType
} from '@/utils/orderStatus'
import zhCN from '@/i18n/locales/zh-CN.js'
import en from '@/i18n/locales/en.js'

// 中文文案不允许出现在映射的 text 里 —— text 必须是 i18n key，
// 否则切换语言时状态名不会跟着刷新（这是本模块存在的核心约定）。
const CJK = /[\u4e00-\u9fff]/
const I18N_KEY = /^[a-z_]+(\.[a-z_]+)+$/

const hasKey = (obj, path) =>
  path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj) !== undefined

describe('orderStatus 映射（订单/采购状态展示的唯一数据源）', () => {
  it('订单状态 text 必须是 i18n key（不是中文文案）', () => {
    for (const cfg of Object.values(ORDER_STATUS_MAP)) {
      expect(cfg.text).not.toMatch(CJK)
      expect(cfg.text).toMatch(I18N_KEY)
    }
  })

  it('采购状态 text 必须是 i18n key（不是中文文案）', () => {
    for (const cfg of Object.values(PURCHASE_STATUS_MAP)) {
      expect(cfg.text).not.toMatch(CJK)
      expect(cfg.text).toMatch(I18N_KEY)
    }
  })

  it('映射里出现的每个 key 在 zh-CN 与 en 中都必须存在', () => {
    const keys = new Set([
      ...Object.values(ORDER_STATUS_MAP).map((c) => c.text),
      ...Object.values(PURCHASE_STATUS_MAP).map((c) => c.text),
      'order.unknown'
    ])
    for (const key of keys) {
      expect(hasKey(zhCN, key), `zh-CN 缺少 ${key}`).toBe(true)
      expect(hasKey(en, key), `en 缺少 ${key}`).toBe(true)
    }
  })

  it('状态 4「已取消」在订单与采购两侧都必须是 info（刚修复的不一致，防回归）', () => {
    expect(ORDER_STATUS_MAP[4].type).toBe('info')
    expect(PURCHASE_STATUS_MAP[4].type).toBe('info')
    expect(getOrderStatusType(4)).toBe('info')
    expect(getPurchaseStatusType(4)).toBe('info')
  })

  it('订单状态取值映射正确', () => {
    expect(getOrderStatusText(0)).toBe('order.status_pending')
    expect(getOrderStatusText(1)).toBe('order.status_paid')
    expect(getOrderStatusText(2)).toBe('order.status_shipped')
    expect(getOrderStatusText(3)).toBe('order.status_completed')
    expect(getOrderStatusText(4)).toBe('order.status_canceled')
    expect(getOrderStatusType(0)).toBe('warning')
    expect(getOrderStatusType(3)).toBe('success')
  })

  it('采购状态取值映射正确', () => {
    expect(getPurchaseStatusText(0)).toBe('purchase.status_pending')
    expect(getPurchaseStatusText(1)).toBe('purchase_manage.audit_approved')
    expect(getPurchaseStatusText(2)).toBe('order.status_completed')
    expect(getPurchaseStatusText(3)).toBe('purchase_manage.audit_rejected')
    expect(getPurchaseStatusText(4)).toBe('purchase.status_canceled')
    expect(getPurchaseStatusType(3)).toBe('danger')
  })

  it('未知状态回退 order.unknown / info（订单与采购两侧一致）', () => {
    for (const bad of [5, 99, -1, undefined, null, 'x']) {
      expect(getOrderStatusText(bad)).toBe('order.unknown')
      expect(getOrderStatusType(bad)).toBe('info')
      expect(getPurchaseStatusText(bad)).toBe('order.unknown')
      expect(getPurchaseStatusType(bad)).toBe('info')
    }
  })
})
