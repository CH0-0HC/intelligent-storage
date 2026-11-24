// 金额渲染的统一出口（2026-10-10 新增）。
//
// 【要解决的问题】全站金额有三种写法并存：
//   · `¥{{ row.raw }}`        —— 直接吐库里的原值，没有千分位、小数位看后端心情
//   · `.toFixed(2)`           —— 有两位小数但没有千分位
//   · `toLocaleString('zh-CN', {minimumFractionDigits:2, ...})` —— 首页统计卡在用
// 采购单最刺眼：一笔 `100000000` 直接糊在表格里，既没有 ¥ 也没有分隔符，
// 读成「一千万还是一亿」全靠数零。这里把格式化收口，后续其余页面可以逐个接过来。
//
// 【为什么不带 ¥】模板里大量是 `¥{{ x }}` 这种写法，工具若自带 ¥ 会出现双 `¥`。
// 需要货币符号的场合显式传 `{ currency: true }`（表格列的 moneyFormatter 就是这么用的）。

/**
 * 把金额渲染成带千分位、两位小数的字符串。
 *
 * @param {number|string|null|undefined} value
 * @param {object} [options]
 * @param {boolean} [options.currency=false] 是否前置 `¥`
 * @param {string} [options.fallback='-'] 空值/非法值的占位
 * @returns {string}
 */
export function formatMoney(value, options = {}) {
  const { currency = false, fallback = '-' } = options
  if (value === null || value === undefined || value === '') return fallback
  const n = Number(value)
  if (Number.isNaN(n)) return fallback
  const text = n.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
  return currency ? `¥${text}` : text
}

/**
 * `el-table-column` 用的 formatter：千分位 + 两位小数 + `¥`。
 *
 * <p>用法：`<el-table-column prop="totalAmount" :label="..." :formatter="moneyFormatter" />`
 *
 * @param {object} row
 * @param {object} column
 * @param {*} value
 */
export function moneyFormatter(row, column, value) {
  return formatMoney(value, { currency: true })
}
