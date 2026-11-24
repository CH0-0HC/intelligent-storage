<template>
  <div class="appearance-panel glass-surface glass-strong glass-xl" role="dialog" :aria-label="t('appearance.title')">
    <!-- 顶部标题 + 总开关 -->
    <div class="ap-head">
      <div class="ap-head-text">
        <span class="ap-title">
          <el-icon><MagicStick /></el-icon>
          {{ t('appearance.title') }}
        </span>
        <span class="ap-sub">{{ t('appearance.subtitle') }}</span>
      </div>
      <el-switch
        v-model="enabled"
        size="small"
        :aria-label="t('appearance.enable')"
      />
    </div>

    <!-- 预设：一键到位，滑动前先给个合理起点 -->
    <div class="ap-presets">
      <button
        v-for="preset in presets"
        :key="preset.key"
        type="button"
        class="ap-preset"
        :class="{ 'is-active': activePreset === preset.key }"
        :title="preset.hint"
        @click="handlePreset(preset)"
      >
        {{ t(`appearance.preset_${preset.key}`) }}
      </button>
    </div>

    <div class="ap-body" :class="{ 'is-disabled': !enabled }">
      <div class="ap-row" v-for="item in sliders" :key="item.key">
        <div class="ap-row-head">
          <span class="ap-label">{{ t(`appearance.${item.key}`) }}</span>
          <span class="ap-value">{{ formatValue(item) }}</span>
        </div>
        <el-slider
          :model-value="valueOf(item.key)"
          :min="item.min"
          :max="item.max"
          :step="item.step"
          :disabled="!enabled"
          :show-tooltip="false"
          :aria-label="t(`appearance.${item.key}`)"
          @input="(v) => handleInput(item.key, v)"
          @mousedown="startDrag"
          @mouseup="endDrag"
          @touchstart.passive="startDrag"
          @touchend="endDrag"
        />
      </div>
    </div>

    <!-- 底部操作 -->
    <div class="ap-foot">
      <button type="button" class="ap-action" @click="handleReset">
        <el-icon><Refresh /></el-icon>
        {{ t('appearance.reset') }}
      </button>
      <span class="ap-hint">{{ t('appearance.hint') }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { storeToRefs } from 'pinia'
import { MagicStick, Refresh } from '@element-plus/icons-vue'
import { useSettingsStore } from '@/stores/settings'
import { GLASS_LIMITS, setDragging, alphaFromSlider as alphaFromSliderUtil, alphaToSlider as alphaToSliderUtil } from '@/utils/glass'

const { t } = useI18n()
const settingsStore = useSettingsStore()
const { glassEnabled: enabled, glassBlur, glassAlpha, glassSat } = storeToRefs(settingsStore)

// 滑块项直接由 GLASS_LIMITS 生成：取值范围只在 utils/glass.js 定义一处，
// 避免"组件里写 0~40、运行时又按另一套 clamp"这种不一致。
const sliders = [
  { key: 'blur', ...GLASS_LIMITS.blur, unit: 'px' },
  { key: 'alpha', min: 0, max: 100, step: 1, default: 50, unit: '%', mapped: true },
  { key: 'sat', ...GLASS_LIMITS.sat, unit: '%' }
]

/**
 * 透明度滑块刻意跑在 0~100 的「显示区间」，而不是直接跑 alpha 原值（0.05~1）。
 * 换算逻辑放在 utils/glass.js 并有单测覆盖（alphaFromSlider / alphaToSlider），
 * 避免"组件里一套、运行时另一套"的不一致。
 */
const alphaFromSlider = (v) => alphaFromSliderUtil(v)
const alphaToSlider = (a) => alphaToSliderUtil(a)

const presets = [
  { key: 'clear', hint: 'blur 6 / alpha 0.3', blur: 6, alpha: 0.3, sat: 140 },
  { key: 'standard', hint: 'blur 18 / alpha 0.5', blur: 18, alpha: 0.5, sat: 160 },
  { key: 'thick', hint: 'blur 26 / alpha 0.72', blur: 26, alpha: 0.72, sat: 180 }
]

/** 滑块显示值：blur/sat 直接用原值，alpha 走上面的映射 */
const valueOf = (key) => {
  if (key === 'blur') return glassBlur.value
  if (key === 'sat') return glassSat.value
  return alphaToSlider(glassAlpha.value)
}

const formatValue = (item) => {
  const raw = valueOf(item.key)
  return item.percent ? `${raw}%` : `${raw}${item.unit}`
}

/**
 * 滑块 input：只写 store（store 的 watch 会同步写入 CSS 变量）。
 * 这里刻意不做任何额外计算或 DOM 查询 —— 拖动路径上只留一次状态赋值。
 */
const handleInput = (key, value) => {
  const num = Number(value)
  if (!Number.isFinite(num)) return
  if (key === 'alpha') {
    settingsStore.setGlass({ alpha: alphaFromSlider(num) })
  } else {
    settingsStore.setGlass({ [key]: num })
  }
}

/** 拖动期：启用 will-change 并临时关闭过渡，保证跟手 */
const startDrag = () => setDragging(true)
const endDrag = () => setDragging(false)

const activePreset = computed(() => {
  // 用"滑块显示值"比较，避免 alpha 在 0.05~1 区间做浮点比较时精度不够
  const cur = { blur: glassBlur.value, alpha: alphaToSlider(glassAlpha.value), sat: glassSat.value }
  const match = presets.find(
    (p) =>
      Math.abs(p.blur - cur.blur) < 0.5 &&
      Math.abs(alphaToSlider(p.alpha) - cur.alpha) <= 1 &&
      Math.abs(p.sat - cur.sat) < 0.5
  )
  return match ? match.key : ''
})

const handlePreset = (preset) => {
  settingsStore.applyGlassPreset(preset)
}

const handleReset = () => {
  settingsStore.resetGlass()
}
</script>

<style scoped>
.appearance-panel {
  width: 320px;
  max-width: calc(100vw - 24px);
  padding: 16px;
  /* 面板自身也是玻璃面，但要更"实"：里面全是小字号标签与数值，
     跟着卡片一起变透明会让背后正文透上来把文字糊掉。
     用 --glass-panel-alpha（随 alpha 单调递减）保证任何透明度下都可读。 */
  background-color: rgb(var(--glass-tint-strong-rgb) / var(--glass-panel-alpha)) !important;
  background-image: var(--glass-sheen-gradient);
  backdrop-filter: blur(calc(var(--glass-blur) * 1.4px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1.4px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  border: 1px solid var(--glass-border-thick);
  box-shadow:
    0 18px 50px var(--glass-shadow-strong),
    inset 0 1px 0 var(--glass-highlight);
  /* 面板是滚动/拖动的高频重绘区：单独提层，避免拖动滑块时把父级布局一并重绘 */
  transform: translateZ(0);
}

.ap-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.ap-head-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.ap-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.ap-sub {
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.4;
}

.ap-presets {
  display: flex;
  gap: 6px;
  margin-bottom: 14px;
}

.ap-preset {
  flex: 1;
  padding: 6px 4px;
  font-size: 12px;
  color: var(--text-secondary);
  background-color: rgb(var(--glass-tint-rgb) / var(--glass-alpha-light));
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  cursor: pointer;
  transition:
    background-color var(--glass-dur) var(--glass-ease),
    border-color var(--glass-dur) var(--glass-ease),
    color var(--glass-dur) var(--glass-ease);
}

.ap-preset:hover {
  background-color: rgb(var(--glass-tint-strong-rgb) / calc(var(--glass-alpha-light) * 1.8));
  border-color: var(--glass-border-thick);
  color: var(--text-primary);
}

.ap-preset.is-active {
  color: #fff;
  background-color: var(--accent-color);
  border-color: var(--accent-color);
}

.ap-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition: opacity var(--glass-dur) var(--glass-ease);
}

.ap-body.is-disabled {
  opacity: 0.45;
}

.ap-row {
  padding: 2px 0 6px;
}

.ap-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.ap-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.ap-value {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--accent-color);
  font-weight: 600;
}

.ap-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--glass-border-light);
}

.ap-action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  background-color: rgb(var(--glass-tint-rgb) / var(--glass-alpha-light));
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  cursor: pointer;
  transition:
    background-color var(--glass-dur) var(--glass-ease),
    color var(--glass-dur) var(--glass-ease);
}

.ap-action:hover {
  background-color: var(--accent-hover);
  color: var(--accent-color);
}

.ap-hint {
  font-size: 10px;
  color: var(--text-muted);
  text-align: right;
  line-height: 1.3;
}

/* 小屏：面板占满可用宽度，避免贴着屏幕边缘溢出 */
@media (max-width: 480px) {
  .appearance-panel {
    width: calc(100vw - 20px);
    padding: 14px;
  }

  .ap-hint {
    display: none;
  }
}
</style>
