import { describe, it, expect } from 'vitest'
import {
  parseBackendTime,
  remainingMs,
  formatRemaining,
  shouldShowCountdown
} from './payCountdown'

// 订单付款倒计时工具的单测。
//
// 【为什么值得测】这里有两个「错了没人立刻发现、但在真机上一定翻车」的点：
//   1) 后端 LocalDateTime 序列化出来的是无时区 ISO 串（2026-10-09T23:50:00）。
//      直接 new Date(str) 在 Safari 上得到 Invalid Date —— 测试环境（Node/Chrome
//      内核）却一切正常，属于典型的「只在部分用户机上坏」。
//      所以这里显式锁定：不依赖 Date 的字符串解析行为，自己拆字段构造。
//   2) 倒计时归零后必须显示 00:00 而不是负数：后台任务是按周期扫描的，
//      归零到真正取消之间有窗口，显示 -00:37 会让人以为功能坏了。

describe('payCountdown / parseBackendTime', () => {
  it('解析无时区的 LocalDateTime 串（按本地时间）', () => {
    const ts = parseBackendTime('2026-10-09T23:50:00')
    const d = new Date(ts)
    expect(d.getFullYear()).toBe(2026)
    expect(d.getMonth()).toBe(9) // 10 月
    expect(d.getDate()).toBe(9)
    expect(d.getHours()).toBe(23)
    expect(d.getMinutes()).toBe(50)
    expect(d.getSeconds()).toBe(0)
  })

  it('兼容空格分隔与缺省秒', () => {
    expect(parseBackendTime('2026-10-09 08:05')).toBe(
      new Date(2026, 9, 9, 8, 5, 0).getTime()
    )
  })

  it('空值/不可解析 → null（不倒计时，也不抛异常）', () => {
    expect(parseBackendTime(null)).toBeNull()
    expect(parseBackendTime(undefined)).toBeNull()
    expect(parseBackendTime('')).toBeNull()
    expect(parseBackendTime('不是时间')).toBeNull()
  })

  it('支持 Date 实例与毫秒时间戳', () => {
    const d = new Date(2026, 0, 1, 0, 0, 0)
    expect(parseBackendTime(d)).toBe(d.getTime())
    expect(parseBackendTime(1767225600000)).toBe(1767225600000)
  })
})

describe('payCountdown / remainingMs', () => {
  const deadline = '2026-10-09T10:00:00'
  const base = new Date(2026, 9, 9, 9, 55, 0).getTime() // 截止前 5 分钟

  it('未到期 → 剩余为正', () => {
    expect(remainingMs(deadline, base)).toBe(5 * 60 * 1000)
  })

  it('已过期 → 剩余为负（由调用方决定怎么显示）', () => {
    expect(remainingMs(deadline, new Date(2026, 9, 9, 10, 1, 0).getTime()))
      .toBe(-60 * 1000)
  })

  it('截止时间缺失 → null', () => {
    expect(remainingMs(null, base)).toBeNull()
  })
})

describe('payCountdown / formatRemaining', () => {
  it('常规剩余时间渲染为 mm:ss', () => {
    expect(formatRemaining(12 * 60 * 1000 + 34 * 1000)).toBe('12:34')
    expect(formatRemaining(59 * 1000)).toBe('00:59')
  })

  it('超过一小时带上小时位', () => {
    expect(formatRemaining(3600 * 1000 + 61 * 1000)).toBe('1:01:01')
  })

  it('【关键】归零与负数统一显示 00:00，不出现负号', () => {
    expect(formatRemaining(0)).toBe('00:00')
    expect(formatRemaining(-1500)).toBe('00:00')
  })

  it('非法输入 → 空串（上层据此不渲染这一行）', () => {
    expect(formatRemaining(null)).toBe('')
    expect(formatRemaining(undefined)).toBe('')
    expect(formatRemaining(NaN)).toBe('')
  })
})

describe('payCountdown / shouldShowCountdown', () => {
  const now = new Date(2026, 9, 9, 10, 0, 0).getTime()

  it('待付款且在时限内 → 显示倒计时', () => {
    expect(shouldShowCountdown({ payDeadline: '2026-10-09T10:05:00' }, now)).toBe(true)
  })

  it('已过期 → 不再显示倒计时（交由「即将自动取消」文案兜底）', () => {
    expect(shouldShowCountdown({ payDeadline: '2026-10-09T09:59:00' }, now)).toBe(false)
  })

  it('后端未回填 payDeadline（非待付款订单）→ 不显示', () => {
    expect(shouldShowCountdown({ payDeadline: null }, now)).toBe(false)
    expect(shouldShowCountdown({}, now)).toBe(false)
    expect(shouldShowCountdown(null, now)).toBe(false)
  })
})
