import { describe, it, expect } from 'vitest'
import { detectStockClamp } from '@/utils/stockClamp'

/**
 * 【测试报告 P3-2 · 2026-10-11】购物车数量超库存被静默钳制
 *
 * 报告原文：「详情页加购 9999 > 库存 494 会 toast 提示并钳制；
 * 购物车内把数量改成 600 则无任何提示直接回弹为 494。」
 *
 * 交验结论：✅ 属实，而且根因比报告写的更具体 —— Element Plus 的
 * `el-input-number.setCurrentValue()` 里有一句 `if (oldVal === newVal && value) return`。
 * 当**当前值已经是库存上限**时，夹回的值恰好等于原值，`change` 事件根本不派发，
 * 于是既不调接口也不弹提示，输入框默默跳回。详情页能提示是因为它走的是另一条路。
 *
 * 本文件锁住「怎么判断被夹了」这个唯一逻辑；组件侧的接线由 Cart.spec.js 钉死。
 */
describe('detectStockClamp · 购物车数量超库存检测（测试报告 P3-2）', () => {
  it('键入值 > 库存时返回夹取详情（报告里的 600 > 494）', () => {
    expect(detectStockClamp(600, 494)).toEqual({ typed: 600, stock: 494 })
  })

  it('键入值正好等于库存不算夹取（这是 Element Plus 不派发 change 的那一档）', () => {
    expect(detectStockClamp(494, 494)).toBeNull()
  })

  it('键入值小于库存不算夹取', () => {
    expect(detectStockClamp(10, 494)).toBeNull()
    expect(detectStockClamp(1, 1)).toBeNull()
  })

  it('字符串入参也要能比较（input 事件给的是解析后的数，但容错）', () => {
    expect(detectStockClamp('600', '494')).toEqual({ typed: 600, stock: 494 })
    expect(detectStockClamp('10', 494)).toBeNull()
  })

  it('库存缺失 / 不可解析时返回 null，不误报', () => {
    expect(detectStockClamp(600, undefined)).toBeNull()
    expect(detectStockClamp(600, null)).toBeNull()
    expect(detectStockClamp(600, '')).toBeNull()
    expect(detectStockClamp(null, 494)).toBeNull()
    expect(detectStockClamp('', 494)).toBeNull()
    expect(detectStockClamp(undefined, 494)).toBeNull()
  })

  it('用户清空输入框（null）时不得当成 0 而误报', () => {
    // Number(null) === 0，若不挡这一档会把「清空」判成超库存
    expect(detectStockClamp(null, 494)).toBeNull()
  })

  it('库存为 0 或负数属于数据异常，交给既有的「已无库存」告警处理', () => {
    expect(detectStockClamp(600, 0)).toBeNull()
    expect(detectStockClamp(600, -1)).toBeNull()
  })
})
