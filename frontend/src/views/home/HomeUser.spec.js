// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

// ============================================================================
// 用户端首页（登录后落地页）渲染测试
//
// 【为什么首页也要测】用户提的缺陷原话是「用户登录后的界面缺少对应提示」——
// 而登录后落地的是首页，不是「我的订单」。商家端在订单管理页有整条预警，
// 首页却什么都没有，于是「有单没付款」这件事在用户最该看到的地方完全静默。
// 这里锁住：首页顶部必须出现未付款预警（含最早一笔的剩余时限与去付款入口）。
//
// 另见 OrderManage.spec.js 顶部关于 jsdom + createApp 方案与 textContent 的说明。
// ============================================================================

const mockOrders = vi.hoisted(() => ({ value: { code: 200, data: { records: [], total: 0 } } }))

vi.mock('@/api/order', () => ({
  getOrderList: vi.fn(() => Promise.resolve(mockOrders.value))
}))
vi.mock('@/api/product', () => ({
  getHotProducts: vi.fn(() => Promise.resolve({ code: 200, data: [] }))
}))
vi.mock('@/api/cart', () => ({
  getCartList: vi.fn(() => Promise.resolve({ code: 200, data: [] }))
}))

import HomeUser from './HomeUser.vue'
import zhCN from '@/i18n/locales/zh-CN.js'

function order(overrides = {}) {
  return {
    id: 5,
    orderNo: 'ORD-1',
    payAmount: 100,
    orderStatus: 0,
    payStatus: 0,
    payDeadline: '2026-10-09T23:55:00',
    createTime: '2026-10-09T23:40:00',
    ...overrides
  }
}

let app = null
let host = null

/** 等渲染稳定后返回页面文本（首页有订单/购物车/热门商品三条并行异步链） */
async function settle(maxRounds = 15) {
  let prev = null
  for (let i = 0; i < maxRounds; i++) {
    await new Promise((r) => setTimeout(r, 10))
    await nextTick()
    const cur = host.textContent || ''
    if (prev !== null && cur === prev && cur.length > 0) return cur
    prev = cur
  }
  return host.textContent || ''
}

async function mountAndReadText() {
  host = document.createElement('div')
  document.body.appendChild(host)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }]
  })
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })

  app = createApp(HomeUser)
  app.use(router)
  app.use(i18n)
  app.config.warnHandler = () => {}
  await router.isReady()
  app.mount(host)
  return settle()
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date(2026, 9, 9, 23, 50, 0))
})

afterEach(() => {
  if (app) {
    app.unmount()
    app = null
  }
  if (host) {
    host.remove()
    host = null
  }
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('用户端首页 · 登录后即可见未付款提示', () => {
  it('有未付款订单时顶部出现预警：单数 + 最早剩余时限 + 去付款', async () => {
    mockOrders.value = { code: 200, data: { records: [order()], total: 1 } }

    const text = await mountAndReadText()

    expect(text).toContain('你有 1 笔订单尚未付款')
    expect(text).toContain('最早一笔将在')
    expect(text).toContain('后自动取消，请尽快完成付款')
    expect(text).toContain('去付款')
    // 剩余时间形如「05:00 后自动取消」（23:50 → 23:55）
    expect(text).toMatch(/\d{2}:\d{2}\s*后自动取消/)
  })

  it('「待付款」统计卡片的脚注变成会走动的倒计时', async () => {
    mockOrders.value = { code: 200, data: { records: [order()], total: 1 } }

    const text = await mountAndReadText()

    expect(text).toContain('后未付款订单将自动取消')
  })

  it('没有未付款订单时不出现预警（且脚注回到静态文案）', async () => {
    mockOrders.value = {
      code: 200,
      data: { records: [order({ orderStatus: 1, payStatus: 1, payDeadline: null })], total: 1 }
    }

    const text = await mountAndReadText()

    expect(text).not.toContain('尚未付款')
    expect(text).toContain('尽快完成支付')
  })

  it('倒计时已归零 → 预警切到「系统正在自动取消」而不是留在 00:00', async () => {
    mockOrders.value = {
      code: 200,
      data: { records: [order({ payDeadline: '2026-10-09T23:49:00' })], total: 1 }
    }

    const text = await mountAndReadText()

    expect(text).toContain('有订单的付款时间已到，系统正在自动取消')
    expect(text).not.toMatch(/-\d{1,2}:\d{2}/)
  })

  it('payDeadline 缺失时给出通用提醒，不自己推算期限', async () => {
    mockOrders.value = { code: 200, data: { records: [order({ payDeadline: null })], total: 1 } }

    const text = await mountAndReadText()

    expect(text).toContain('请尽快完成付款，超时订单将被系统自动取消')
    expect(text).not.toMatch(/\d{2}:\d{2}\s*后自动取消/)
  })
})
