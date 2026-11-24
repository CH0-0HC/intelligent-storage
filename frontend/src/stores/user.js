import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * 登录态 Pinia store —— token / userInfo 的唯一权威来源。
 *
 * 【为什么要有它】
 * 在本次收敛之前，「登录态」实际上有两条并行的轨道：
 *   1) `localStorage.token` / `localStorage.userInfo` —— 被 router 守卫、
 *      axios 拦截器、Layout 退出登录、Password 改密登出等多处**直接读写**；
 *   2) `utils/auth.js` 的角色判定 —— 也是自己去读 localStorage。
 * 两轨并存的问题：状态没有单一出口，任何一处漏写就会留下「半截登录态」
 * （典型：清了 token 但 userInfo 还在，router 守卫按 role 放行）。
 *
 * 现在把「token / userInfo 从哪来、写到哪去」全部收敛到本 store，
 * localStorage 只在**本文件**里被读写（持久化职责），其余代码一律走
 * `stores/user` 或 `utils/auth` 的封装。
 *
 * 【兼容性关键】
 * `router` 守卫与 `request.js` 拦截器可能在 pinia 激活之前执行，
 * 因此 `utils/auth.js` 必须能「拿不到 store 时回退读 localStorage」，
 * 不能裸调 useUserStore()。本 store 自身在初始化时也会从 localStorage
 * 载入，保证「刷新页面」后状态与持久化一致。
 */

const TOKEN_KEY = 'token'
const USER_INFO_KEY = 'userInfo'

/**
 * 容错读取 userInfo：localStorage 缺失 / 被写坏时返回 {}，
 * 绝不抛异常（历史缺陷 P0-6：裸 JSON.parse 会把整个布局白屏）。
 */
function readUserInfoFromStorage() {
  try {
    const raw = localStorage.getItem(USER_INFO_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch (e) {
    console.warn('[userStore] userInfo 解析失败，按未登录处理', e)
    return {}
  }
}

export const useUserStore = defineStore('user', () => {
  // 初始值即从 localStorage 恢复，保证刷新后不丢登录态。
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const userInfo = ref(readUserInfoFromStorage())

  // ===== getters（setup store 中返回 computed 即等价于 getter）=====
  const role = computed(() => userInfo.value?.role || '')
  const merchantId = computed(() => userInfo.value?.merchantId ?? null)
  const isAdmin = computed(() => role.value === 'admin')
  const isMerchant = computed(() => role.value === 'merchant')
  const isUser = computed(() => role.value === 'user')
  const nickname = computed(
    () => userInfo.value?.nickname || userInfo.value?.username || ''
  )

  // ===== actions =====

  /**
   * 登录成功时写入。payload 即 login 接口返回的 data
   * （{ token, userId, username, nickname, role, avatar, merchantId }），
   * 整体存入 userInfo，与历史行为一致（localStorage.userInfo = JSON.stringify(res.data)）。
   */
  function setLogin(payload) {
    const data = payload && typeof payload === 'object' ? payload : {}
    token.value = data.token || ''
    userInfo.value = data
    if (data.token) {
      localStorage.setItem(TOKEN_KEY, data.token)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
    localStorage.setItem(USER_INFO_KEY, JSON.stringify(data))
  }

  /** 清空登录态（登出 / 401 失效 / 改密后登出）：state 与 localStorage 一并清。 */
  function clear() {
    token.value = ''
    userInfo.value = {}
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_INFO_KEY)
  }

  /** 从 localStorage 重新载入（用于外部写入后同步，或测试重置）。 */
  function syncFromStorage() {
    token.value = localStorage.getItem(TOKEN_KEY) || ''
    userInfo.value = readUserInfoFromStorage()
  }

  return {
    token,
    userInfo,
    role,
    merchantId,
    isAdmin,
    isMerchant,
    isUser,
    nickname,
    setLogin,
    clear,
    syncFromStorage
  }
})
