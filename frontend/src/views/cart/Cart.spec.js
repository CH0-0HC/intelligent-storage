// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

// ============================================================================
// 购物车页级渲染测试 —— 锁住测试报告 #4 与 #2 的两条契约
//
// 【#4：0 勾选不能开出空结算弹窗】报告写「已选 0/4 件时点结算 → 弹出确认订单
// 弹窗（¥0.00），提交才被后端『请至少选择一个商品』拦截」。但源码里结算按钮带
// `:disabled="selectedCount === 0"`，checkout() 里也有一道 early return ——
// 两处都在 2026-09-22 就存在（git log 可查）。**本次交验未能复现 #4**，
// 于是把「按钮确实禁用、点了也确实不开弹窗」写成断言钉死：
// 若哪天有人把 :disabled 删掉，这条会红；也给报告作者一份可复核的证据，
// 而不是靠我口头说「代码里有」。
//
// 【#2：收货电话必须校验】这里断言的是**规则确实挂到了渲染出来的表单上**
// —— 报告原文是「字段带 * 但未挂验证规则」，所以要验的正是"挂上了没"：
// el-form 的 :rules 里必须有 phone 的格式规则，且该规则对 "abc" 会报错。
//
// 【为什么不断言「填 abc 后 createOrder 不被调用」】
// 那条断言依赖 Element Plus 的 el-form.validate() 走通。实测在 vitest/jsdom 下
// 走不通：单独造一个只有一项的最小 el-form，自定义 validator 也完全不会被调用
// （EP 内部 new AsyncValidator(...) 抛出，错误对象没有 fields，form.validate()
// 直接 resolve）。Register.vue 在真实浏览器里校验是好的（报告里也验证了字段级红字），
// 说明这是**测试环境**问题、不是产品代码问题 —— 所以不在这里下假阴性的断言。
//
// 【真正的硬闸门在后端】OrderCreateDTO 的 shippingPhone 上有 @Pattern 且
// createOrder 方法加了 @Valid，GlobalExceptionHandler 统一转 400。
// 即便前端漏校验，shippingPhone="abc" 也进不了库 —— 报告里那个脏数据不会再产生。
// ============================================================================

const mockCart = vi.hoisted(() => ({ value: [] }))
const createOrderMock = vi.hoisted(() =>
  vi.fn(() => Promise.resolve({ code: 200, data: { id: 99, orderNo: 'ORD-TEST' } }))
)

vi.mock('@/api/cart', () => ({
  getCartList: vi.fn(() => Promise.resolve({ code: 200, data: mockCart.value })),
  updateCartQuantity: vi.fn(() => Promise.resolve({ code: 200, message: 'ok' })),
  removeFromCart: vi.fn(() => Promise.resolve({ code: 200, message: 'ok' })),
  updateCartSelected: vi.fn(() => Promise.resolve({ code: 200, message: 'ok' })),
  updateAllCartSelected: vi.fn(() => Promise.resolve({ code: 200, message: 'ok' }))
}))
vi.mock('@/api/order', () => ({ createOrder: createOrderMock }))

import Cart from './Cart.vue'
import zhCN from '@/i18n/locales/zh-CN.js'

let app = null
let host = null

function item(overrides = {}) {
  return {
    id: 1,
    productId: 10,
    productName: '测试商品',
    productPrice: 100,
    quantity: 1,
    selected: 1,
    // 结算闸门读的是 productStock（不是 stock）：缺了它会被 selectedOutOfStock
    // 判成「已无库存」，在收货校验之前就提前 return —— 排查时极易误判成校验没生效
    productStock: 50,
    ...overrides
  }
}

/** 等渲染稳定（连续两轮文本不再变化），理由同 OrderList.spec.js */
async function settle(maxRounds = 15) {
  let prev = null
  for (let i = 0; i < maxRounds; i++) {
    await new Promise((r) => setTimeout(r, 10))
    await nextTick()
    const cur = document.body.textContent || ''
    if (prev !== null && cur === prev && cur.length > 0) return cur
    prev = cur
  }
  return document.body.textContent || ''
}

async function mountCart() {
  host = document.createElement('div')
  document.body.appendChild(host)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }]
  })
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })

  app = createApp(Cart)
  app.use(router)
  app.use(i18n)
  app.config.warnHandler = () => {}
  await router.isReady()
  app.mount(host)
  return settle()
}

/** 结算按钮：文案是「结算」或「结算（N）」 */
const checkoutButton = () =>
  [...document.body.querySelectorAll('button')].find((b) => b.textContent.includes('结算'))

/** 结算弹窗（el-dialog 会 teleport 到 body，所以查 document.body 而不是 host） */
const dialog = () => document.body.querySelector('.el-dialog')

const sleep = async (ms = 40) => {
  await new Promise((r) => setTimeout(r, ms))
  await nextTick()
}

async function openCheckoutDialog() {
  checkoutButton().click()
  await sleep(80)
  return dialog()
}

/** 取 DOM 节点背后的组件实例 props（Vue 挂在根元素上的 __vueParentComponent） */
const compProps = (el) => el && el.__vueParentComponent && el.__vueParentComponent.props

afterEach(() => {
  if (app) {
    app.unmount()
    app = null
  }
  if (host) {
    host.remove()
    host = null
  }
  mockCart.value = []
  createOrderMock.mockClear()
  document.body.innerHTML = ''
})

describe('购物车 · 已选 0 件时的结算闸门（测试报告 #4）', () => {
  it('「已选 0/2」时结算按钮是禁用的', async () => {
    mockCart.value = [item({ selected: 0 }), item({ id: 2, selected: 0 })]
    const text = await mountCart()

    expect(text).toContain('已选')
    expect(text).toContain('/ 2')

    const btn = checkoutButton()
    expect(btn).toBeTruthy()
    expect(btn.disabled).toBe(true)
  })

  it('点了禁用的结算按钮不会弹出「确认订单」空清单，也不会发下单请求', async () => {
    mockCart.value = [item({ selected: 0 }), item({ id: 2, selected: 0 })]
    await mountCart()

    checkoutButton().click()
    await sleep(80)

    expect(document.body.textContent).not.toContain('确认订单')
    expect(createOrderMock).not.toHaveBeenCalled()
  })

  it('有勾选时按钮可用、点击能正常打开确认订单弹窗（对照组：闸门不是把功能焊死）', async () => {
    mockCart.value = [item({ selected: 1 }), item({ id: 2, selected: 0 })]
    await mountCart()

    const btn = checkoutButton()
    expect(btn.disabled).toBe(false)
    expect(btn.textContent).toContain('（1）')

    const dlg = await openCheckoutDialog()
    expect(dlg).toBeTruthy()
    expect(document.body.textContent).toContain('确认订单')
  })
})

describe('购物车 · 收货表单确实挂上了校验规则（测试报告 #2）', () => {
  it('弹窗里的 el-form 拿到了 rules，三个字段都有 prop', async () => {
    mockCart.value = [item({ selected: 1 })]
    await mountCart()
    const dlg = await openCheckoutDialog()
    expect(dlg).toBeTruthy()

    const form = dlg.querySelector('.el-form')
    const formRules = compProps(form)?.rules
    expect(formRules).toBeTruthy()
    expect(Object.keys(formRules).sort()).toEqual(['address', 'name', 'phone'])

    // prop 必须挂上：Element Plus 靠 prop 把 form-item 关联到 rules
    // （报告原文「字段带 * 但未挂验证规则」的直接成因就是少了它）
    const props = [...dlg.querySelectorAll('.el-form-item')]
      .map((el) => compProps(el)?.prop)
      .filter(Boolean)
    expect(props).toEqual(['name', 'phone', 'address'])
  })

  it('电话的格式规则会对 "abc" 报错，且对合法号放行', async () => {
    mockCart.value = [item({ selected: 1 })]
    await mountCart()
    const dlg = await openCheckoutDialog()

    const rules = compProps(dlg.querySelector('.el-form'))?.rules
    const phoneRules = rules?.phone || []
    // 顺序即契约：[0] 必填、[1] 找得到才校验格式（空值不重复报错）
    expect(phoneRules.length).toBe(2)
    const formatRule = phoneRules[1]
    expect(typeof formatRule.validator, '电话必须存在带 validator 的格式规则').toBe('function')

    const check = (value) =>
      new Promise((resolve) => formatRule.validator(null, value, resolve))

    const bad = await check('abc')
    expect(bad).toBeInstanceOf(Error)
    expect(bad.message).toBe('收货人电话格式不正确')

    const badLen = await check('1234567890987') // 报告里的历史 13 位脏数据
    expect(badLen).toBeInstanceOf(Error)

    expect(await check('13800138000')).toBeUndefined()
  })

  it('必填规则用 validator 实现（触发时才调 t()），空值报出的是文案而非 i18n key', async () => {
    mockCart.value = [item({ selected: 1 })]
    await mountCart()
    const dlg = await openCheckoutDialog()

    const rules = compProps(dlg.querySelector('.el-form'))?.rules
    const nameRule = rules.name.find((r) => typeof r.validator === 'function')
    // 构造 rules 时用的是 setup 时那一刻的 t；validator 在触发时才调 t()
    const err = await new Promise((r) => nameRule.validator(null, '', r))
    expect(err).toBeInstanceOf(Error)
    expect(err.message).toBe('请输入收货人姓名')
  })
})

// ============================================================================
// 购物车 · 数量超库存必须有明确提示（测试报告 P3-2 · 2026-10-11）
//
// 【报告原文】「详情页加购 9999 > 库存 494 会 toast 提示并钳制；
// 购物车内把数量改成 600 则**无任何提示**直接回弹为 494。」
//
// 【交验：属实，但根因比报告写的更死】不是"忘了加提示"，而是 Element Plus 的
// el-input-number.setCurrentValue() 里 `if (oldVal === newVal && value) return` ——
// 当前值已是库存上限时，夹回值恰好等于原值 ⇒ **change 事件根本不派发**
// ⇒ 既不调接口也不弹提示。所以只在 @change 上补提示是修不好的。
// 修复：@input 记下夹取前的原始键入值，@change 与 @blur 两路消费。
//
// 这里直接在 jsdom 里模拟"输入 600 → 失焦"，断言提示确实出现 —— 会变红。
// ============================================================================
describe('购物车 · 数量超库存的提示（测试报告 P3-2）', () => {
  const quantityInput = () => document.querySelector('.el-input-number input')

  /** 模拟浏览器里"键入 → 失焦"的真实事件顺序（change 在 blur 之前） */
  async function typeAndBlur(rawValue) {
    const input = quantityInput()
    expect(input, '购物车数量输入框必须渲染出来').toBeTruthy()
    input.value = String(rawValue)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await sleep(20)
    input.dispatchEvent(new Event('change', { bubbles: true }))
    input.dispatchEvent(new FocusEvent('blur'))
    await sleep(150)
  }

  it('当前数量已是库存上限时，再输更大的数 → 提示「已调整为 494」', async () => {
    mockCart.value = [item({ quantity: 494, productStock: 494, selected: 1 })]
    await mountCart()
    await typeAndBlur(600)

    expect(document.body.textContent).toContain('数量已超出库存，已调整为 494')
  })

  it('当前数量不是上限（300 → 600）同样要有提示，不能只修一档', async () => {
    mockCart.value = [item({ quantity: 300, productStock: 494, selected: 1 })]
    await mountCart()
    await typeAndBlur(600)

    expect(document.body.textContent).toContain('数量已超出库存，已调整为 494')
  })

  it('未超库存时不得出现夹取提示（对照组：不是无脑弹）', async () => {
    mockCart.value = [item({ quantity: 10, productStock: 494, selected: 1 })]
    await mountCart()
    await typeAndBlur(20)

    expect(document.body.textContent).not.toContain('数量已超出库存')
  })

  it('输入框被夹回后确实回弹到库存值，不会停留在 600', async () => {
    mockCart.value = [item({ quantity: 494, productStock: 494, selected: 1 })]
    await mountCart()
    await typeAndBlur(600)

    expect(quantityInput().value).toBe('494')
  })
})
