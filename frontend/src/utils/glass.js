/**
 * ============================================================================
 *  Liquid Glass 运行时：把滑块参数实时写到 :root 的 CSS 变量上
 * ============================================================================
 *
 * 【为什么不走 Vue 响应式驱动样式】
 * 滑块拖动时每秒可产生 60~120 次 input 事件。若把这些值绑到组件 style 上，
 * 每次变化都会触发组件重渲染 → 重算样式 → 大面积重绘。本项目的 DOM 很重
 * （表格动辄上百行、看板几十张卡），这条路径必然掉帧。
 *
 * 因此这里改为**命令式**写法：
 *   1. 参数只写进 `document.documentElement.style`（CSS 自定义属性）；
 *   2. 浏览器只重算受影响的绘制，不触碰 Vue 虚拟 DOM；
 *   3. 用 requestAnimationFrame 把一帧内的多次调用**合并成一次写入**，
 *      即使用户用键盘连按或快速拖动，每帧最多也只写一遍。
 *
 * 【可读性护栏】
 * `frost`（提亮层）与 `alpha`（不透明度）反向联动：越透明 → 提亮越强，
 * 保证把透明度拉到最低时正文依然清晰。
 * ============================================================================
 */

/** 参数默认值与取值范围（供滑块组件复用，避免两处写死不一致） */
export const GLASS_LIMITS = {
  blur: { min: 0, max: 40, step: 1, default: 18 },
  alpha: { min: 0.05, max: 1, step: 0.01, default: 0.5 },
  sat: { min: 100, max: 220, step: 5, default: 160 }
}

/** 可读性护栏：提亮系数。alpha 越低，白/黑提亮层越强。 */
const FROST_BASE = { light: 0.06, dark: 0.05 }
const FROST_GAIN = { light: 0.24, dark: 0.16 }

/**
 * 透明度滑块的「显示区间 ↔ alpha 原值」换算。
 *
 * 滑块跑在 0~100，alpha 实际区间是 0.05~1。这层映射不是装饰：
 * el-slider 的键盘/点击步长 = 显示区间 ÷ 100，若直接绑 alpha 原值，
 * 方向键一次只动 0.01（1%），且下限 0.05 仅 5 格就到底，
 * 用户会以为滑块失灵。映射成 0~100 后每次 1%、可精确取到下限。
 */
export function alphaFromSlider(value) {
  const v = Number.isFinite(Number(value)) ? Number(value) : 50
  const clamped = clamp(v, 0, 100)
  return Number((GLASS_LIMITS.alpha.min + (clamped / 100) * (GLASS_LIMITS.alpha.max - GLASS_LIMITS.alpha.min)).toFixed(4))
}

export function alphaToSlider(alpha) {
  const a = Number.isFinite(Number(alpha)) ? Number(alpha) : GLASS_LIMITS.alpha.default
  const { min, max } = GLASS_LIMITS.alpha
  const clamped = clamp(a, min, max)
  return Math.round(((clamped - min) / (max - min)) * 100)
}

/** 主题 → 提亮层的取值范围（深色主题不能加太多白，否则发灰） */
const themeKey = () => (document.documentElement.classList.contains('dark') ? 'dark' : 'light')

const clamp = (value, min, max) => Math.min(max, Math.max(min, value))

/**
 * 把数值约束到合法区间。外部传入（localStorage 曾被手改、导入配置等）
 * 的脏数据不能直接写进 CSS —— 例如 `--glass-blur: NaN` 会让整块玻璃失效。
 */
export function normalizeGlassParams(raw = {}) {
  const out = {}
  for (const key of Object.keys(GLASS_LIMITS)) {
    const { min, max, default: dft } = GLASS_LIMITS[key]
    const num = Number(raw[key])
    out[key] = Number.isFinite(num) ? clamp(num, min, max) : dft
  }
  return out
}

/**
 * 计算提亮层强度：透明度越低，提亮越强，抵消"透过去看不清"的问题。
 *
 * 入参必须做有限性校验：`computeFrost(NaN)` 会算成 NaN，
 * 写进 `--glass-frost` 后 `rgb(... / calc(...))` 整条声明失效，
 * 表现为「拖过某个值之后玻璃突然全没了」——测试里专门锁了这个回归。
 */
export function computeFrost(alpha, theme = 'light') {
  const gain = FROST_GAIN[theme] ?? FROST_GAIN.light
  const base = FROST_BASE[theme] ?? FROST_BASE.light
  const safe = Number.isFinite(Number(alpha)) ? clamp(Number(alpha), 0, 1) : GLASS_LIMITS.alpha.default
  return Number((base + (1 - safe) * gain).toFixed(4))
}

// ---- rAF 合并写入 ---------------------------------------------------------

let pending = null
let rafId = 0

function flush() {
  rafId = 0
  const params = pending
  pending = null
  if (!params) return

  const root = document.documentElement
  const style = root.style

  // 只写"纯数字"，算式全部交给 CSS 的 calc() —— 见 glass.scss 顶部说明
  style.setProperty('--glass-blur', String(params.blur))
  style.setProperty('--glass-alpha', String(params.alpha))
  style.setProperty('--glass-sat', String(params.sat))
  style.setProperty('--glass-frost', String(computeFrost(params.alpha, themeKey())))
}

/**
 * 实时应用一组毛玻璃参数。
 * 同一帧内的多次调用只会真正写一次样式。
 */
export function applyGlass(rawParams) {
  pending = normalizeGlassParams(rawParams)

  if (rafId) return
  // SSR / 测试环境可能没有 rAF，退化为同步执行
  if (typeof requestAnimationFrame !== 'function') {
    flush()
    return
  }
  rafId = requestAnimationFrame(flush)
}

/** 立即写入（不等待下一帧）：用于首屏初始化，避免闪一下默认玻璃再跳到用户设置 */
export function applyGlassSync(rawParams) {
  pending = normalizeGlassParams(rawParams)
  if (rafId && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
  flush()
}

/**
 * 重新计算提亮层。主题切换时调用：同样的透明度，浅色与深色需要的提亮量不同。
 * @param {number} alpha 当前不透明度
 */
export function refreshGlassFrost(alpha) {
  const value = Number.isFinite(Number(alpha)) ? Number(alpha) : GLASS_LIMITS.alpha.default
  document.documentElement.style.setProperty('--glass-frost', String(computeFrost(value, themeKey())))
}

/** 整体开关：给 <html> 加/去 .glass-off（见 glass.scss 第 9 节） */
export function setGlassEnabled(enabled) {
  document.documentElement.classList.toggle('glass-off', !enabled)
}

/**
 * 设备能力探测：判断是否值得开启模糊。
 * 只在「连 WebGL 都拿不到」这种明显软件渲染的场景下建议关闭。
 */
export function canUseBackdropFilter() {
  if (typeof document === 'undefined') return false
  const supported =
    typeof CSS !== 'undefined' &&
    (CSS.supports?.('backdrop-filter', 'blur(1px)') || CSS.supports?.('-webkit-backdrop-filter', 'blur(1px)'))
  if (!supported) return false

  try {
    const canvas = document.createElement('canvas')
    return !!(canvas.getContext('webgl') || canvas.getContext('experimental-webgl'))
  } catch {
    return false
  }
}

/**
 * 拖动期开关：拖动时给 <body> 打标记，
 * 借此启用 will-change、并临时关闭过渡（见 glass.scss 第 2 节）。
 */
export function setDragging(dragging) {
  document.body.classList.toggle('glass-dragging', !!dragging)
}
