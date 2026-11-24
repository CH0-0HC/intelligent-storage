import { ref, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { getAfterSaleReasons } from '@/api/afterSale'

/**
 * 售后「原因」字典的组合式函数。
 *
 * 【2026-10-10 修复的是什么】
 * `AfterSaleServiceImpl` 在**申请那一刻**把原因文案写进了 `after_sale.reason`
 * （`entity.setReason(AfterSaleRule.reasonText(code))`），那是一份**中文快照**：
 * 申请后界面语言再怎么切，这一列永远是中文。`AfterSaleDetail` / `AfterSaleList` /
 * `AfterSaleManage` 三处正是直接渲染 `row.reason`，所以英文界面下会冒出中文。
 *
 * 【怎么修】改成**优先按 `reasonCode` 从字典取**：字典由后端按 `lang` 下发（见
 * `api/afterSale.js` 的 `currentLang`），所以会随界面语言变化；
 * 字典里没有该 code（历史脏数据 / 未来新增）时，退回库里那份快照作为兜底，
 * 再不行才显示原始 code —— 任何情况下都不会显示空白。
 *
 * 【为什么是 composable 而不是各页面自己拉】三个页面要完全一样的三件事：
 * 挂载时拉、**切语言时重拉**、按 code 取文案。抄三遍必然漏掉「切语言重拉」那一半，
 * 这次的缺陷正是这么来的。
 *
 * @returns {{ reasons: import('vue').Ref<Record<string,string>>, text: (code: string|null|undefined, fallback?: string|null) => string }}
 */
export function useAfterSaleReasons() {
  const { locale } = useI18n()
  const reasons = ref({})

  const load = async () => {
    try {
      const res = await getAfterSaleReasons()
      reasons.value = res.data || {}
    } catch (e) {
      // 字典拿不到时 `text()` 会退到行内快照，界面不至于空
      console.error('加载售后原因字典失败', e)
    }
  }

  onMounted(load)
  // 字典文案是后端按 lang 下发的，切语言不重拉就会停在切换前那种语言
  watch(locale, load)

  /**
   * @param {string|null|undefined} code 原因编码（后端给的 reasonCode）
   * @param {string|null} [fallback] 行里持久化的中文快照，字典缺失时使用
   */
  const text = (code, fallback) => {
    if (reasons.value[code]) return reasons.value[code]
    if (fallback) return fallback
    return code || '-'
  }

  return { reasons, text }
}
