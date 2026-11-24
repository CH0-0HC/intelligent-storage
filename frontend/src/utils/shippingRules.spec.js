import { describe, it, expect } from 'vitest'
import { makeShippingRules } from './shippingRules'
import { PHONE_PATTERN } from './phonePattern'

// ============================================================================
// 收货表单校验规则（测试报告#2 的回归锁）
//
// 被测对象是「购物车结算」与「商品详情立即购买」两个弹窗共用的那份 rules。
// 报告里的原始症状是：表单只有 label 上的 required 星号、没有 rules/prop，
// 收货电话填 `abc` 照样下单，shipping_phone="abc" 直接入库。
// 这里把规则本身的行为钉死，避免哪天有人把 prop 删了或把 pattern 放宽
// 而没人发现（页面级渲染测试覆盖不到「规则有没有挂上去」这一层）。
//
// 【为什么直接调 validator 而不是挂到 el-form 上】
// 挂表单要起 dialog + 异步渲染，断言里混进一堆时序；而 validator 是纯函数，
// 给什么值就报什么错，失败信息也直指规则本身。表单是否真的挂上了 prop，
// 由 Cart.spec.js / 页面渲染侧覆盖。
// ============================================================================

// t 用恒等函数：断言里直接比对 i18n key，不把文案钉死在测试里
// （文案由 check-i18n-keys.mjs 保证中英成对，属于另一条防线）
const t = (key) => key
const rules = makeShippingRules(t)

/** 跑单条规则，resolve 为 undefined 表示通过，Error 表示被拦下 */
const run = (rule, value) =>
  new Promise((resolve) => rule.validator(null, value, resolve))

const nameRule = rules.name[0]
const phoneRequired = rules.phone[0]
const phoneFormat = rules.phone[1]
const addressRule = rules.address[0]

describe('makeShippingRules — 收货人 / 地址必填', () => {
  it('空字符串、纯空格、undefined 都被判为未填', async () => {
    for (const bad of ['', '   ', undefined, null]) {
      expect((await run(nameRule, bad))?.message).toBe('order.shipping_name_placeholder')
      expect((await run(addressRule, bad))?.message).toBe('order.shipping_address_placeholder')
    }
  })

  it('正常填写通过', async () => {
    expect(await run(nameRule, '张三')).toBeUndefined()
    expect(await run(addressRule, '上海市浦东新区xx路1号')).toBeUndefined()
  })
})

describe('收货电话 —— 报告#2 的核心', () => {
  it('「abc」必须被拦下（这就是库里 phone="abc" 的来源）', async () => {
    expect((await run(phoneFormat, 'abc'))?.message).toBe('order.shipping_phone_invalid')
  })

  it('位数不对的号被拦下（历史脏数据 1234567890987 是 13 位）', async () => {
    for (const bad of ['1234567890987', '1380013800', '12800138000', '23800138000', '03800138000']) {
      expect(await run(phoneFormat, bad), `应拦截 ${bad}`).toBeInstanceOf(Error)
    }
  })

  it('合法大陆手机号通过', async () => {
    for (const good of ['13800138000', '19912345678', '17000000000']) {
      expect(await run(phoneFormat, good), `应放行 ${good}`).toBeUndefined()
    }
  })

  it('空值交给必填规则去报错，格式规则本身放行（避免同一条错误弹两次）', async () => {
    expect(await run(phoneFormat, '')).toBeUndefined()
    expect(await run(phoneFormat, undefined)).toBeUndefined()
    expect((await run(phoneRequired, ''))?.message).toBe('order.shipping_phone_placeholder')
  })

  it('两端都只收 trim 后的值 —— 用户不小心多打空格不该被当成非法号', async () => {
    expect(await run(phoneFormat, ' 13800138000 ')).toBeUndefined()
  })
})

describe('PHONE_PATTERN 与后端 @Pattern 同口径', () => {
  it('正则本身不接受 11 位以外或第二位非 3-9 的号', () => {
    expect(PHONE_PATTERN.test('13800138000')).toBe(true)
    expect(PHONE_PATTERN.test('12800138000')).toBe(false)
    expect(PHONE_PATTERN.test('138001380000')).toBe(false)
    expect(PHONE_PATTERN.test('abc')).toBe(false)
    expect(PHONE_PATTERN.test('')).toBe(false)
  })

  it('与后端 OrderCreateDTO 里 @Pattern 的字面量一致（避免两端口径漂移）', async () => {
    const { readFileSync } = await import('node:fs')
    const { fileURLToPath } = await import('node:url')
    const path = await import('node:path')
    const here = path.dirname(fileURLToPath(import.meta.url))
    const dto = readFileSync(
      path.resolve(here, '../../../backend/src/main/java/com/ai/smartinventory/dto/OrderCreateDTO.java'),
      'utf8'
    )
    // Java 里 ^1[3-9]\\d{9}$ （正则字面量多一层转义）
    expect(dto).toContain('@Pattern(regexp = "^1[3-9]\\\\d{9}$"')
  })
})
