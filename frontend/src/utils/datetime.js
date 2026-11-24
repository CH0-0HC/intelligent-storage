// 后端时间字段的统一渲染出口。
//
// 【2026-10-10】修的是什么
// 后端 `LocalDateTime` 经 Jackson 序列化成 ISO 无时区字符串（`2026-10-10T16:52:25`），
// 而多个页面是**直接把字段塞进模板**渲染的，于是界面上出现
// 「申请时间 2026-10-10T16:52:25」这种带 `T` 的原串：
//   · 售后列表 / 售后详情 / 售后管理的申请·审核·完成时间
//   · 我的订单创建时间、退货规则页的确认收货时间
//   · 采购列表与详情创建时间、工单提交/裁定时间
//   · 用户管理最后登录、库存管理最后入/出库、流水时间、操作日志时间
// 同一个应用里，商家订单页又用的是 `10-10 16:49`（已经格式化），
// 两种形态并存，读起来像是两个系统拼起来的。
//
// 【为什么复用 parseBackendTime 而不是 new Date(str)】
// Safari 对 `new Date('2026-10-09 23:50:00')`、以及部分 ISO 变体会给 Invalid Date；
// parseBackendTime 是本项目**已经验证过**的唯一解析出口（倒计时就靠它），
// 显式拆字段、按本地时间构造。这里只做格式化，不再发明第二套解析。

import { parseBackendTime } from './payCountdown'

const pad = (n) => String(n).padStart(2, '0')

/**
 * 把后端时间渲染成 `YYYY-MM-DD HH:mm`。
 *
 * @param {string|number|Date|null|undefined} value 后端返回的时间字段
 * @param {object} [options]
 * @param {boolean} [options.withSeconds=false] 是否带秒（审计/日志类字段用得上）
 * @param {string} [options.empty='-'] 空值占位
 * @returns {string}
 */
export function formatDateTime(value, options = {}) {
  const { withSeconds = false, empty = '-' } = options
  const ts = parseBackendTime(value)
  if (ts === null) return empty
  const d = new Date(ts)
  const base =
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    ` ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return withSeconds ? `${base}:${pad(d.getSeconds())}` : base
}

/**
 * 表格列用的 `el-table-column` formatter 签名。
 *
 * <p>直接把 {@link formatDateTime} 绑到 `:formatter` 上会多收一个 column 参数，
 * 所以包一层，模板里写 `:formatter="dateTimeFormatter"` 即可。
 *
 * @param {object} row
 * @param {string} column
 * @param {string|number} value
 */
export function dateTimeFormatter(row, column, value) {
  return formatDateTime(value, { withSeconds: false })
}

/**
 * 带秒版本（操作日志、库存流水这类需要精确到秒的场景）。
 */
export function dateTimeFormatterSeconds(row, column, value) {
  return formatDateTime(value, { withSeconds: true })
}
