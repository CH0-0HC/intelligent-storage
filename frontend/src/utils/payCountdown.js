// 订单付款倒计时的唯一工具。
//
// 【为什么单独抽出来】「待付款订单还有多久被系统自动取消」这件事，商家端（订单管理）
// 和用户端（我的订单）都要展示，而且必须**显示同一个数字**。若两边各写一份
// 「createTime + 15 分钟」的算法，一处改了配置另一处不同步，就会出现
// 「用户看到还剩 3 分钟、商家看到还剩 8 分钟」这种没人能解释的现象。
//
// 设计口径：
//   · 截止时间由**后端**给（order.payDeadline = createTime + 配置的超时分钟数），
//     前端只负责把它渲染成「剩余 mm:ss」，绝不自己推算超时时长；
//   · 后端对非待付款订单不回填 payDeadline（返回 null），前端据此不渲染倒计时。

import { ref, onBeforeUnmount } from 'vue'

/**
 * 一个每秒自增的「当前时间」引用，用于驱动倒计时刷新。
 *
 * <p>用 `ref(Date.now())` + 定时器，而不是让每个订单各自起一个 setInterval：
 * 一页最多 100 个订单，各自计时就是 100 个定时器，且它们触发时刻不同步，
 * 视觉上会看到数字一块一块地跳。这里统一用一个心跳，所有倒计时同一帧更新。
 *
 * @param {number} intervalMs 心跳间隔（默认 1000ms）
 * @returns {import('vue').Ref<number>} 当前毫秒时间戳
 */
export function useNowTicker(intervalMs = 1000) {
  const now = ref(Date.now())
  const timer = setInterval(() => {
    now.value = Date.now()
  }, intervalMs)
  // 组件卸载必须清掉：定时器回调里写的是 ref，泄漏后虽然不报错，
  // 但会让「已离开的页面」一直参与刷新，属于典型的静默泄漏。
  onBeforeUnmount(() => clearInterval(timer))
  return now
}

/**
 * 把后端返回的时间字符串解析成毫秒时间戳。
 *
 * <p>后端 Java 的 LocalDateTime 经 Jackson 序列化为 ISO 无时区格式
 * （`2026-10-09T23:50:00`）。直接 `new Date(str)` 在部分浏览器（Safari）
 * 上会得到 Invalid Date，因此这里显式拆分字段、按**本地时间**构造 ——
 * 与服务器同处一个时区时结果准确，也避免了 `new Date('2026-10-09 23:50:00')`
 * 在 Safari 上同样失效的问题。
 *
 * @param {string|number|Date|null|undefined} value
 * @returns {number|null} 毫秒时间戳；无法解析时返回 null
 */
export function parseBackendTime(value) {
  if (value === null || value === undefined || value === '') return null
  if (value instanceof Date) return value.getTime()
  if (typeof value === 'number') return value

  const s = String(value).trim()
  const m = s.match(
    /^(\d{4})-(\d{2})-(\d{2})[T\s](\d{2}):(\d{2})(?::(\d{2}))?/
  )
  if (!m) {
    const fallback = new Date(s)
    return Number.isNaN(fallback.getTime()) ? null : fallback.getTime()
  }
  const [, y, mo, d, h, mi, sec] = m
  return new Date(
    Number(y),
    Number(mo) - 1,
    Number(d),
    Number(h),
    Number(mi),
    sec ? Number(sec) : 0
  ).getTime()
}

/**
 * 距离截止时刻还剩多少毫秒。
 *
 * @returns {number|null} 剩余毫秒数（可能为负，表示已过期）；无法解析时返回 null
 */
export function remainingMs(deadline, now = Date.now()) {
  const ts = parseBackendTime(deadline)
  if (ts === null) return null
  return ts - now
}

/**
 * 把剩余毫秒格式化成 `mm:ss`（超过 1 小时为 `h:mm:ss`）。
 *
 * <p>负数统一显示 `00:00`：倒计时归零到后台任务真正取消之间有一个扫描间隔，
 * 显示 `-00:12` 只会让人以为出错了。
 */
export function formatRemaining(ms) {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return ''
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

/**
 * 判断订单当前是否处于「待付款且尚未超时」——即应当展示付款倒计时。
 *
 * <p>只有同时满足「后端给了 payDeadline」与「剩下的时间还没走完」才展示；
 * 已过期的单子由后台任务很快取消，这里不再显示一个恒为 00:00 的倒计时。
 */
export function shouldShowCountdown(order, now = Date.now()) {
  if (!order) return false
  const ms = remainingMs(order.payDeadline, now)
  return ms !== null && ms > 0
}
