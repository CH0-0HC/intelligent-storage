import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'
import en from './locales/en'

const DEFAULT_LANG = 'zh-CN'

const savedLang = localStorage.getItem('locale') || DEFAULT_LANG

const i18n = createI18n({
  legacy: false,
  locale: savedLang,
  fallbackLocale: DEFAULT_LANG,
  messages: {
    'zh-CN': zhCN,
    'en': en
  },
  globalInjection: true,
  silentTranslationWarn: true
})

export default i18n

/**
 * 当前界面语言 → 后端 `lang` 查询参数（`zh` / `en`）。
 *
 * 【2026-10-10】售后原因字典与「客服裁定标准」原文都由**后端下发**
 * （项目既有约定：前端不硬编码第二套文案，避免两端各写一份然后漂移）。
 * 这两份字典原先只有中文，切到 English 后下拉仍是一列中文、裁定标准还是一整段中文。
 * 现由后端按 `lang` 返回对应语言，各 api 在请求出口统一调用本函数附参 ——
 * 调用点无需改动，新调用方也不会漏传。
 *
 * 认不出的一律回退 `zh`（默认语言），与加参数之前的行为一致。
 */
export function currentLang() {
  const l = i18n.global.locale && i18n.global.locale.value
  return l && String(l).toLowerCase().startsWith('en') ? 'en' : 'zh'
}
