import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { GLASS_LIMITS, normalizeGlassParams, applyGlassSync, refreshGlassFrost, setGlassEnabled, canUseBackdropFilter } from '@/utils/glass'

const GLASS_STORAGE_KEY = 'glassSettings'

/**
 * 读取持久化的毛玻璃设置。
 * localStorage 可能被手工改成非法 JSON，解析失败必须回落到默认值，
 * 否则整个应用会在启动阶段抛错白屏（与 utils/auth.getUserInfo 同一套防御思路）。
 */
function readStoredGlass() {
  const defaults = {
    enabled: true,
    blur: GLASS_LIMITS.blur.default,
    alpha: GLASS_LIMITS.alpha.default,
    sat: GLASS_LIMITS.sat.default
  }
  try {
    const raw = localStorage.getItem(GLASS_STORAGE_KEY)
    if (!raw) return defaults
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return defaults
    return {
      // enabled 显式存了才用存储值，否则跟随默认（避免早期版本无此字段时被当成 false）
      enabled: typeof parsed.enabled === 'boolean' ? parsed.enabled : true,
      ...normalizeGlassParams(parsed)
    }
  } catch (error) {
    console.warn('[settings] glassSettings 解析失败，已回落到默认值', error)
    return defaults
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const locale = ref(localStorage.getItem('locale') || 'zh-CN')
  const theme = ref(localStorage.getItem('theme') || 'light')

  // ---- 毛玻璃（Liquid Glass）设置 ----
  const storedGlass = readStoredGlass()
  const glassEnabled = ref(storedGlass.enabled)
  const glassBlur = ref(storedGlass.blur)
  const glassAlpha = ref(storedGlass.alpha)
  const glassSat = ref(storedGlass.sat)

  // EP 组件语言由 App.vue 的 <el-config-provider> 统一承担，
  // 这里只维护应用自身 locale 与持久化。
  const setLocale = (lang) => {
    locale.value = lang
    localStorage.setItem('locale', lang)
  }

  const setTheme = (newTheme) => {
    theme.value = newTheme
    localStorage.setItem('theme', newTheme)
    updateThemeClass(newTheme)
    // 浅色/深色玻璃需要的「提亮层」强度不同，切主题后按新主题重算，
    // 否则深色主题下会残留浅色的强提亮，玻璃看起来发灰。
    refreshGlassFrost(glassAlpha.value)
  }

  const updateThemeClass = (currentTheme) => {
  const html = document.documentElement
  html.classList.remove('dark', 'light')
  html.classList.add(currentTheme)
}

const toggleTheme = () => {
  const newTheme = theme.value === 'light' ? 'dark' : 'light'
  setTheme(newTheme)
}

  const toggleLocale = () => {
    setLocale(locale.value === 'zh-CN' ? 'en' : 'zh-CN')
  }

  const initTheme = () => {
  updateThemeClass(theme.value)
}

  // ---- 毛玻璃写回：任何一项变化都立即生效（applyGlassSync 内部做值域约束）----
  const persistGlass = () => {
    try {
      localStorage.setItem(
        GLASS_STORAGE_KEY,
        JSON.stringify({
          enabled: glassEnabled.value,
          blur: glassBlur.value,
          alpha: glassAlpha.value,
          sat: glassSat.value
        })
      )
    } catch (error) {
      // 隐私模式 / 配额写满时写失败不应影响当前会话的视觉调节
      console.warn('[settings] glassSettings 持久化失败', error)
    }
  }

  const applyGlassNow = () => {
    setGlassEnabled(glassEnabled.value)
    applyGlassSync({
      blur: glassBlur.value,
      alpha: glassAlpha.value,
      sat: glassSat.value
    })
    // 主题可能刚切过，统一再核一次提亮层，保证与当前主题匹配
    refreshGlassFrost(glassAlpha.value)
  }

  /** 一次性写入多个毛玻璃参数（滑块拖动时调用，只触发一次写样式） */
  const setGlass = (patch = {}) => {
    if (!patch || typeof patch !== 'object') return
    if (typeof patch.enabled === 'boolean') glassEnabled.value = patch.enabled
    if (patch.blur !== undefined) glassBlur.value = Number(patch.blur)
    if (patch.alpha !== undefined) glassAlpha.value = Number(patch.alpha)
    if (patch.sat !== undefined) glassSat.value = Number(patch.sat)
  }

  /** 恢复默认外观 */
  const resetGlass = () => {
    setGlass({
      enabled: true,
      blur: GLASS_LIMITS.blur.default,
      alpha: GLASS_LIMITS.alpha.default,
      sat: GLASS_LIMITS.sat.default
    })
  }

  /** 应用预设（预设是一组参数快照） */
  const applyGlassPreset = (preset) => {
    if (!preset) return
    setGlass(preset)
  }

  const toggleGlass = () => {
    glassEnabled.value = !glassEnabled.value
  }

  /**
   * 初始化毛玻璃：必须在 app.mount 之前调用。
   * 用同步写入而非 rAF，避免首帧先按默认玻璃渲染、再跳到用户设置造成闪动。
   *
   * 探测到设备明显无法承担 backdrop-filter（无 WebGL / 不支持该属性）时，
   * 自动降级为「关闭毛玻璃」，防止在没有 GPU 合成的机器上整页卡顿。
   */
  const initGlass = () => {
    if (!canUseBackdropFilter()) {
      glassEnabled.value = false
    }
    applyGlassNow()
  }

  watch(theme, (newTheme) => {
    updateThemeClass(newTheme)
  })

  // 逐项侦听而不是深侦听对象：滑块只改一个字段时，只跑一次写样式
  watch([glassBlur, glassAlpha, glassSat], () => {
    applyGlassSync({
      blur: glassBlur.value,
      alpha: glassAlpha.value,
      sat: glassSat.value
    })
    refreshGlassFrost(glassAlpha.value)
    persistGlass()
  })

  watch(glassEnabled, () => {
    setGlassEnabled(glassEnabled.value)
    persistGlass()
  })

  return {
    locale,
    theme,
    setLocale,
    setTheme,
    toggleTheme,
    toggleLocale,
    initTheme,

    // 毛玻璃
    glassEnabled,
    glassBlur,
    glassAlpha,
    glassSat,
    setGlass,
    resetGlass,
    applyGlassPreset,
    toggleGlass,
    initGlass
  }
})
