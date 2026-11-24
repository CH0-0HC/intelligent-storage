import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import {
  GLASS_LIMITS,
  normalizeGlassParams,
  computeFrost,
  applyGlassSync,
  setGlassEnabled,
  refreshGlassFrost,
  alphaFromSlider,
  alphaToSlider
} from '@/utils/glass'

// 这些测试盯的是「滑块的数值边界」与「CSS 变量写回」两条链路：
// 前者决定脏数据（localStorage 被手改、导入配置）会不会把整块玻璃弄坏，
// 后者决定拖动滑块时界面到底有没有真的变。

const rootStyle = () => document.documentElement.style

// 注意：jsdom 里 `style.cssText = ''` 不会可靠地清掉自定义属性，
// 必须逐个 removeProperty，否则用例之间会互相污染（后写的值看不见）。
const GLASS_PROPS = ['--glass-blur', '--glass-alpha', '--glass-sat', '--glass-frost']

const clearGlassProps = () => {
  for (const prop of GLASS_PROPS) rootStyle().removeProperty(prop)
}

const readProp = (prop) => rootStyle().getPropertyValue(prop).trim()

describe('GLASS_LIMITS', () => {
  it('三个可调参数都定义了 min/max/default，且默认值落在区间内', () => {
    for (const key of ['blur', 'alpha', 'sat']) {
      const limit = GLASS_LIMITS[key]
      expect(limit, key).toBeTruthy()
      for (const field of ['min', 'max', 'default']) {
        expect(Number.isFinite(limit[field]), `${key}.${field}`).toBe(true)
      }
      expect(limit.default).toBeGreaterThanOrEqual(limit.min)
      expect(limit.default).toBeLessThanOrEqual(limit.max)
    }
  })
})

describe('normalizeGlassParams', () => {
  it('缺省入参回落到默认值', () => {
    const out = normalizeGlassParams()
    expect(out.blur).toBe(GLASS_LIMITS.blur.default)
    expect(out.alpha).toBe(GLASS_LIMITS.alpha.default)
    expect(out.sat).toBe(GLASS_LIMITS.sat.default)
  })

  it('空对象同样回落默认值', () => {
    expect(normalizeGlassParams({})).toEqual({
      blur: GLASS_LIMITS.blur.default,
      alpha: GLASS_LIMITS.alpha.default,
      sat: GLASS_LIMITS.sat.default
    })
  })

  it('超上限/超下限被夹到边界（防止 localStorage 被手改后糊掉界面）', () => {
    expect(normalizeGlassParams({ blur: 9999 }).blur).toBe(GLASS_LIMITS.blur.max)
    expect(normalizeGlassParams({ blur: -50 }).blur).toBe(GLASS_LIMITS.blur.min)
    expect(normalizeGlassParams({ alpha: 5 }).alpha).toBe(GLASS_LIMITS.alpha.max)
    expect(normalizeGlassParams({ alpha: -1 }).alpha).toBe(GLASS_LIMITS.alpha.min)
    expect(normalizeGlassParams({ sat: 10000 }).sat).toBe(GLASS_LIMITS.sat.max)
  })

  it('NaN / 字符串数字 / null 都不会写出 NaN 到 CSS', () => {
    // 关键回归点：`--glass-blur: NaN` 会让 backdrop-filter 整条声明失效，
    // 表现为"滑块拖过之后就再也没玻璃效果了"。
    for (const bad of [NaN, 'abc', null, undefined, {}, []]) {
      const out = normalizeGlassParams({ blur: bad, alpha: bad, sat: bad })
      expect(Number.isFinite(out.blur)).toBe(true)
      expect(Number.isFinite(out.alpha)).toBe(true)
      expect(Number.isFinite(out.sat)).toBe(true)
    }
  })

  it('合法字符串数字会被转成数值', () => {
    const out = normalizeGlassParams({ blur: '24', alpha: '0.7', sat: '180' })
    expect(out).toEqual({ blur: 24, alpha: 0.7, sat: 180 })
  })
})

describe('computeFrost', () => {
  it('透明度越低，提亮层越强（可读性护栏的核心不变量）', () => {
    const low = computeFrost(0.05, 'light')
    const high = computeFrost(0.95, 'light')
    expect(low).toBeGreaterThan(high)
  })

  it('提亮值始终落在 0~1 的合法区间', () => {
    for (const alpha of [0, 0.05, 0.5, 1, 1.5, -1, NaN]) {
      for (const theme of ['light', 'dark']) {
        const v = computeFrost(alpha, theme)
        expect(v).toBeGreaterThanOrEqual(0)
        expect(v).toBeLessThanOrEqual(1)
      }
    }
  })

  it('深色主题的提亮量小于浅色（避免发灰）', () => {
    expect(computeFrost(0.2, 'dark')).toBeLessThan(computeFrost(0.2, 'light'))
  })

  it('未知主题回落到 light 而不是 NaN', () => {
    expect(Number.isFinite(computeFrost(0.5, 'nope'))).toBe(true)
  })
})

describe('alphaFromSlider / alphaToSlider —— 滑块显示区间换算', () => {
  it('滑块 0 → alpha 下限、100 → alpha 上限', () => {
    expect(alphaFromSlider(0)).toBeCloseTo(GLASS_LIMITS.alpha.min, 4)
    expect(alphaFromSlider(100)).toBeCloseTo(GLASS_LIMITS.alpha.max, 4)
  })

  it('默认值 0.5 往返一致（不会因换算漂移）', () => {
    const slider = alphaToSlider(GLASS_LIMITS.alpha.default)
    expect(alphaFromSlider(slider)).toBeCloseTo(GLASS_LIMITS.alpha.default, 2)
  })

  it('往返在整条区间上单调且误差可接受', () => {
    let prev = -1
    for (let s = 0; s <= 100; s += 5) {
      const a = alphaFromSlider(s)
      const back = alphaToSlider(a)
      expect(back).toBeCloseTo(s, 0)
      expect(a).toBeGreaterThan(prev)
      prev = a
    }
  })

  it('透明度滑块落在下限时仍可继续精确调节（回归：曾因直接绑 alpha 而"卡死"）', () => {
    // 滑块 0 对应最低不透明度，此时再往右一格必须真的产生变化
    const atMin = alphaFromSlider(0)
    const next = alphaFromSlider(1)
    expect(next).toBeGreaterThan(atMin)
    // 步长应约为 (max-min)/100，而不是被 step 0.01 截断
    expect(next - atMin).toBeCloseTo((GLASS_LIMITS.alpha.max - GLASS_LIMITS.alpha.min) / 100, 4)
  })

  it('越界与非法输入被夹住且不产生 NaN', () => {
    for (const bad of [-50, 200, NaN, 'abc', null, undefined]) {
      expect(Number.isFinite(alphaFromSlider(bad))).toBe(true)
      expect(Number.isFinite(alphaToSlider(bad))).toBe(true)
    }
    expect(alphaFromSlider(-50)).toBeCloseTo(GLASS_LIMITS.alpha.min, 4)
    expect(alphaFromSlider(200)).toBeCloseTo(GLASS_LIMITS.alpha.max, 4)
    expect(alphaToSlider(99)).toBe(100)
    expect(alphaToSlider(-1)).toBe(0)
  })
})

describe('applyGlassSync / setGlassEnabled 写回 DOM', () => {
  beforeEach(() => {
    document.documentElement.className = ''
    clearGlassProps()
  })

  afterEach(() => {
    clearGlassProps()
    document.documentElement.className = ''
  })

  it('把三个旋钮写成纯数字（单位与算式由 CSS calc 负责）', () => {
    applyGlassSync({ blur: 24, alpha: 0.7, sat: 180 })
    expect(readProp('--glass-blur')).toBe('24')
    expect(Number(readProp('--glass-alpha'))).toBeCloseTo(0.7, 5)
    expect(readProp('--glass-sat')).toBe('180')
    // 提亮层也要一并算出，否则拖低透明度时文字会失去护栏
    expect(readProp('--glass-frost')).not.toBe('')
  })

  it('写入的是数值而非 px，保证 calc(… * 1px) 能算出长度', () => {
    applyGlassSync({ blur: 18 })
    const raw = readProp('--glass-blur')
    expect(raw).not.toContain('px')
    expect(Number(raw)).toBe(18)
  })

  it('setGlassEnabled(false) 给 <html> 加 .glass-off，true 时移除', () => {
    setGlassEnabled(false)
    expect(document.documentElement.classList.contains('glass-off')).toBe(true)
    setGlassEnabled(true)
    expect(document.documentElement.classList.contains('glass-off')).toBe(false)
  })

  it('refreshGlassFrost 在深色主题下写出更小的提亮值', () => {
    document.documentElement.classList.add('light')
    refreshGlassFrost(0.3)
    const lightVal = Number(readProp('--glass-frost'))

    document.documentElement.classList.remove('light')
    document.documentElement.classList.add('dark')
    refreshGlassFrost(0.3)
    const darkVal = Number(readProp('--glass-frost'))

    expect(lightVal).toBeGreaterThan(darkVal)
  })

  it('refreshGlassFrost 收到非法值时不写出 NaN', () => {
    refreshGlassFrost(undefined)
    expect(readProp('--glass-frost')).not.toContain('NaN')
    refreshGlassFrost('abc')
    expect(readProp('--glass-frost')).not.toContain('NaN')
  })
})
