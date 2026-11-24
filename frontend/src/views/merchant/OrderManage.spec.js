// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'

// ============================================================================
// 商家端「订单管理」页面级渲染测试（本项目首个页面级 UI 测试）
//
// 【为什么必须测到渲染层】
// 本次缺陷的表现就是**纯视觉的**：「用户没付款，商家端却直接给出确认发货/关闭，
// 中间没有任何付款状态」。这类缺陷用后端接口测试完全测不出来 —— 接口一直是对的，
// 缺的是把 payStatus 画到界面上。而且本项目有一条已知纪律：
// 组件渲染期抛错会连锁毁掉父级 patch（表现为下拉跑到左上角、header 用户名不渲染），
// 因此「能挂载 + 能渲染出关键文案」本身就需要被回归锁住。
//
// 【实现说明】
// 不引入 @vue/test-utils（项目未安装），直接用 createApp + jsdom 挂载真实组件；
// Element Plus 组件由 vite 配置里的 unplugin-vue-components 按需注入，无需手工注册。
// 接口层用 vi.mock 顶掉，测试不产生任何网络请求。
//
// 【用 textContent 而不是 innerText】jsdom 对 innerText 的支持不完整，
// 读取它可能拿到 undefined 而不是渲染结果 —— 那样测试会「永远通过不了」，
// 更糟的是有人会以为是组件坏了。
// ============================================================================

// vi.hoisted：vi.mock 的工厂会在 import 求值阶段就被调用，
// 此时模块顶层 const 还处于 TDZ，直接引用会抛 ReferenceError。
const mockOrders = vi.hoisted(() => ({ value: { code: 200, data: { records: [] } } }))

vi.mock('@/api/order', () => ({
  getOrderList: vi.fn(() => Promise.resolve(mockOrders.value)),
  updateOrderStatus: vi.fn(() => Promise.resolve({ code: 200 })),
  shipOrder: vi.fn(() => Promise.resolve({ code: 200 }))
}))

import OrderManage from './OrderManage.vue'
import zhCN from '@/i18n/locales/zh-CN.js'

/** 造一个「已下单未付款」订单：这正是本次缺陷里商家看不出来的那种单 */
function unpaidOrder(overrides = {}) {
  return {
    id: 5,
    orderNo: 'ORD-UNPAID',
    username: 'demo',
    payAmount: 100,
    orderStatus: 0,
    payStatus: 0,
    payTime: null,
    cancelReason: null,
    // 后端按「创建时间 + 配置的超时分钟数」回填
    payDeadline: '2026-10-09T23:55:00',
    items: [{ id: 1, productName: '测试商品', quantity: 2 }],
    shippingName: '张三',
    shippingPhone: '13800000000',
    createTime: '2026-10-09T23:40:00',
    ...overrides
  }
}

function paidOrder(overrides = {}) {
  return unpaidOrder({
    id: 6,
    orderNo: 'ORD-PAID',
    orderStatus: 1,
    payStatus: 1,
    payTime: '2026-10-09T23:41:00',
    payDeadline: null,
    ...overrides
  })
}

let app = null
let host = null

/** 挂载真实组件（含 i18n），等到异步加载订单完成后返回页面文本 */
async function mountAndReadText() {
  host = document.createElement('div')
  document.body.appendChild(host)

  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  app = createApp(OrderManage)
  app.use(i18n)
  app.config.warnHandler = () => {}
  app.mount(host)

  // loadOrders 是 onMounted 里的异步调用，等两轮宏任务 + 一次渲染
  await new Promise((r) => setTimeout(r, 0))
  await nextTick()
  await new Promise((r) => setTimeout(r, 0))
  await nextTick()
  return host.textContent || ''
}

beforeEach(() => {
  // 把「当前时间」钉在截止时间之前，倒计时才会有非零的剩余值。
  // shouldAdvanceTime 让真实时间继续推进（否则 await setTimeout(0) 会死等），
  // 因此断言里用正则匹配 mm:ss，而不是写死 05:00 —— 否则会因几百毫秒的推进而抖动。
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

describe('商家端订单管理页 · 用户付款状态展示', () => {
  it('未付款订单渲染出「未付款」标签与自动取消倒计时', async () => {
    mockOrders.value = { code: 200, data: { records: [unpaidOrder()] } }

    const text = await mountAndReadText()

    expect(text).toContain('付款状态')
    expect(text).toContain('未付款')
    // 倒计时形如「04:59 后自动取消」（模板换行会带一个空白，故用 \s*）
    expect(text).toMatch(/\d{2}:\d{2}\s*后自动取消/)
  })

  it('存在未付款订单时给出顶部预警条（把「有几单没给钱」提到最前面）', async () => {
    mockOrders.value = {
      code: 200,
      data: { records: [unpaidOrder(), paidOrder()] }
    }

    const text = await mountAndReadText()

    expect(text).toContain('1 笔订单用户尚未付款')
  })

  it('全部已付款时不再显示预警条（避免长期挂着一个无意义的警告）', async () => {
    mockOrders.value = { code: 200, data: { records: [paidOrder()] } }

    const text = await mountAndReadText()

    expect(text).toContain('已付款')
    expect(text).not.toContain('笔订单用户尚未付款')
  })

  it('已付款订单显示付款时间，且不再显示倒计时', async () => {
    mockOrders.value = { code: 200, data: { records: [paidOrder()] } }

    const text = await mountAndReadText()

    expect(text).toContain('付款时间')
    expect(text).toContain('10-09 23:41')
    expect(text).not.toContain('后自动取消')
  })

  it('已取消订单展示取消原因，系统超时取消与用户主动取消文案不同', async () => {
    mockOrders.value = {
      code: 200,
      data: {
        records: [
          unpaidOrder({
            id: 7,
            orderNo: 'ORD-TIMEOUT',
            orderStatus: 4,
            cancelReason: 'AUTO_TIMEOUT',
            payDeadline: null
          }),
          unpaidOrder({
            id: 8,
            orderNo: 'ORD-USER-CANCEL',
            orderStatus: 4,
            cancelReason: 'USER',
            payDeadline: null
          })
        ]
      }
    }

    const text = await mountAndReadText()

    expect(text).toContain('超时未付款，系统自动取消')
    expect(text).toContain('用户取消')
  })

  it('前端不自行推算超时时间：payDeadline 缺失时不渲染倒计时', async () => {
    mockOrders.value = {
      code: 200,
      data: { records: [unpaidOrder({ payDeadline: null })] }
    }

    const text = await mountAndReadText()

    // 仍然要标明未付款（这是本次缺陷的核心诉求）
    expect(text).toContain('未付款')
    // 但绝不能凭空算出一个「15 分钟后取消」——超时时长只有后端说了算
    expect(text).not.toContain('后自动取消')
  })
})
