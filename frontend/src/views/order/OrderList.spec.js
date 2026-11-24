// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

// ============================================================================
// 用户端「我的订单」页面级渲染测试
//
// 【本次要锁住的契约】
// 订单超时未付款会被后台任务自动取消。对用户而言，最糟的体验是：状态突然变成
// 「已取消」而页面上没有任何解释 —— 用户会以为是系统出故障，或者自己误触了什么。
// 2026-10-10 又发现第二层问题：仅有一行 12px 灰字的提示**权重远低于商家端**
// （商家是整条预警 + 倒计时列），用户实测里根本没注意到。因此这里锁住三层提示：
//   · 顶部队列级预警条（有几单没付 + 最早一笔还剩多久 + 一键去付款）；
//   · 卡片内的整行未付款倒计时条（含归零后的「正在自动取消」文案）；
//   · 被系统取消后写出「超时未付款，系统已自动取消」，且与「商家关闭」
//     「用户主动取消」的文案必须不同（否则等于没解释）。
//
// 详见 OrderManage.spec.js 顶部关于「为什么用 jsdom + createApp 而不是 test-utils」
// 以及「为什么读 textContent 而不是 innerText」的说明。
// ============================================================================

const mockOrders = vi.hoisted(() => ({ value: { code: 200, data: { records: [], total: 0 } } }))

vi.mock('@/api/order', () => ({
  getOrderList: vi.fn(() => Promise.resolve(mockOrders.value)),
  updateOrderStatus: vi.fn(() => Promise.resolve({ code: 200 }))
}))

import OrderList from './OrderList.vue'
import zhCN from '@/i18n/locales/zh-CN.js'

function order(overrides = {}) {
  return {
    id: 5,
    orderNo: 'ORD-1',
    payAmount: 100,
    orderStatus: 0,
    payStatus: 0,
    cancelReason: null,
    payDeadline: '2026-10-09T23:55:00',
    createTime: '2026-10-09T23:40:00',
    items: [{ id: 1, productName: '测试商品', price: 50, quantity: 2, productImage: null }],
    afterSale: null,
    ...overrides
  }
}

let app = null
let host = null

/**
 * 等渲染稳定（连续两轮文本不再变化）后返回页面文本。
 *
 * 【为什么不能用固定次数的 await】页面里有两条异步链会交叉：
 * onMounted 的 loadOrders，以及「倒计时归零」watcher 触发的补偿 loadOrders。
 * 后者会把 loading 置真、让顶部预警条短暂消失 —— 用固定等待次数去读文本，
 * 有概率恰好读到中间态而误判「提示没渲染」。
 */
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

  // 路由只是个壳：本页只用到 useRoute() 读 query.highlight 与 router.push，
  // 测试不校验跳转，因此给一个「什么都能匹配」的空路由即可。
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }]
  })
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })

  app = createApp(OrderList)
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

describe('用户端我的订单页 · 未付款提示与倒计时', () => {
  it('顶部出现队列级预警：未付款单数 + 最早一笔剩余时限 + 去付款入口', async () => {
    mockOrders.value = { code: 200, data: { records: [order()], total: 1 } }

    const text = await mountAndReadText()

    expect(text).toContain('你有 1 笔订单尚未付款')
    expect(text).toContain('最早一笔将在')
    expect(text).toContain('后自动取消，请尽快完成付款')
    expect(text).toContain('去付款')
    // 剩余时间形如「05:00 后自动取消」（23:50 → 23:55）。
    // 不能只断言 /\d{2}:\d{2}/ —— 页面上的创建时间 2026-10-09T23:40:00 也含这个形状。
    expect(text).toMatch(/\d{2}:\d{2}\s*后自动取消/)
  })

  it('卡片内有整行未付款倒计时条，且文案明确写出「超时将自动取消」', async () => {
    mockOrders.value = { code: 200, data: { records: [order()], total: 1 } }

    const text = await mountAndReadText()

    expect(text).toContain('未付款 · 请在')
    expect(text).toContain('内完成付款，超时将自动取消订单')
  })

  it('无未付款订单时不出现任何预警（避免长期挂着无意义的横幅）', async () => {
    mockOrders.value = {
      code: 200,
      data: { records: [order({ orderStatus: 1, payStatus: 1, payDeadline: null })], total: 1 }
    }

    const text = await mountAndReadText()

    expect(text).not.toContain('尚未付款')
    expect(text).not.toContain('内完成付款')
  })

  it('被系统超时取消的订单显示「超时未付款，系统已自动取消」', async () => {
    mockOrders.value = {
      code: 200,
      data: {
        records: [order({ orderStatus: 4, cancelReason: 'AUTO_TIMEOUT', payDeadline: null })],
        total: 1
      }
    }

    const text = await mountAndReadText()

    expect(text).toContain('超时未付款，系统已自动取消')
  })

  it('三种取消原因文案互不相同（否则用户仍然看不出发生了什么）', async () => {
    mockOrders.value = {
      code: 200,
      data: {
        records: [
          order({ id: 1, orderStatus: 4, cancelReason: 'AUTO_TIMEOUT', payDeadline: null }),
          order({ id: 2, orderStatus: 4, cancelReason: 'MERCHANT', payDeadline: null }),
          order({ id: 3, orderStatus: 4, cancelReason: 'USER', payDeadline: null })
        ],
        total: 3
      }
    }

    const text = await mountAndReadText()

    expect(text).toContain('超时未付款，系统已自动取消')
    expect(text).toContain('商家已关闭该订单')
    expect(text).toContain('你已主动取消该订单')
  })

  it('倒计时已归零但后台尚未取消 → 文案切到「正在自动取消」，且不出现负数', async () => {
    mockOrders.value = {
      code: 200,
      data: {
        // 截止时间已被当前时间越过：后台任务最多一分钟内才会处理
        records: [order({ payDeadline: '2026-10-09T23:49:00' })],
        total: 1
      }
    }

    const text = await mountAndReadText()

    expect(text).toContain('付款时间已到，订单正在自动取消')
    // 绝不能出现负数倒计时（如 -00:37）。注意不能简单断言不含 '-0' ——
    // 创建时间 2026-10-09 本身就带 '-0'，那样写会误报。
    expect(text).not.toMatch(/-\d{1,2}:\d{2}/)
  })

  it('payDeadline 缺失时不倒计时，但仍要标明未付款', async () => {
    mockOrders.value = {
      code: 200,
      data: { records: [order({ payDeadline: null })], total: 1 }
    }

    const text = await mountAndReadText()

    // 未付款这件事必须说清楚（这是本轮缺陷的核心诉求）
    expect(text).toContain('该订单尚未付款')
    expect(text).toContain('请尽快完成付款，超时订单将被系统自动取消')
    // 但绝不自己拿 createTime 加 15 分钟造一个期限出来 —— 超时时长只有后端说了算
    expect(text).not.toContain('内完成付款，超时将自动取消订单')
    expect(text).not.toMatch(/\d{2}:\d{2}\s*后自动取消/)
  })
})
