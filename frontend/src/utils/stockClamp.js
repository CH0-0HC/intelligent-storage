/**
 * 购物车/详情页「数量超出库存」的钳制检测（2026-10-11 · 测试报告 P3-2）。
 *
 * 【为什么需要这个小工具】
 * Element Plus 的 `el-input-number` 会在内部把超上限的输入静默夹回 `max`，
 * 而它的 `setCurrentValue()` 长这样：
 *
 *     if (oldVal === newVal && value) return   // 夹回原值 ⇒ 直接 return
 *
 * 也就是说：当用户在**已经是库存上限的数量**上再输一个更大的数时，
 * 夹回的值恰好等于当前值，`change` 事件根本不会派发 —— 既不调接口、
 * 也不弹任何提示，输入框只是默默跳回原值。这就是报告里说的
 * 「购物车内把数量改成 600 则无任何提示直接回弹为 494」。
 *
 * 详情页之所以"有提示"，是因为它在 `addToCart` 里走了另一条路；
 * 购物车这条链路上原本没有任何一处能看到"用户到底输了什么"。
 *
 * `el-input-number` 的 `input` 事件恰好在**夹取之前**派发未截断的解析值
 * （`handleInput` 里 `emit(INPUT_EVENT, newVal)` 在 `verifyValue` 之前），
 * 所以只要把这一次输入记下来，就能在 `change`（或补发的 `blur`）里
 * 判断出"刚才被夹掉了"。
 *
 * 【为什么要抽成纯函数】
 * 这段判断是本修复的**唯一逻辑**，抽出来才能被 vitest 直接钉死；
 * 组件里只负责接线，不会因为模板改动而让断言失效。
 *
 * @param {*} typedQuantity 刚才在输入框里键入的原始数值（来自 el-input-number 的 input 事件）
 * @param {*} stock         该商品库存（item.product.stock）
 * @returns {{typed: number, stock: number} | null} 被夹掉时返回夹取详情，否则 null
 */
export function detectStockClamp(typedQuantity, stock) {
  const typed = Number(typedQuantity)
  const max = Number(stock)

  // 任一侧不可解析（用户清空了输入、库存字段缺失等）⇒ 无从比较，不报
  if (!Number.isFinite(typed) || !Number.isFinite(max)) return null
  // stock 为 0 或负数属于数据异常，交给既有的「已无库存」告警去处理
  if (max <= 0) return null
  // 未超上限 ⇒ 没有夹取
  if (typed <= max) return null

  return { typed, stock: max }
}
