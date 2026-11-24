/**
 * 角色与身份工具 —— 前端统一的角色判定入口
 *
 * 【设计说明】
 * 项目原有的角色判断散落在 Layout.vue（菜单）、Home.vue（首页卡片）、
 * Login.vue（登录跳转）、router/index.js（路由守卫）等多处，
 * 每处都手写 `role === 'merchant'` / `role === 'user'` 这样的字符串比较，
 * 一旦新增角色或有拼写差异就会静默失效（例如写成 'Merchant' 或 'seller'）。
 *
 * 这里把「角色从哪来」「如何判断」「菜单/首页如何按角色分流」收敛到一处，
 * 其它文件只调用这里的方法，保证全站行为一致。
 *
 * 【判断依据（权威来源）】
 * 1. 服务端权威来源：Sa-Token 会话中的 userRole / merchantId，
 *    由 AuthController.login() 在登录成功时写入，客户端无法伪造。
 * 2. 前端展示来源：login 接口响应体被整体存入 localStorage.userInfo，
 *    包含 { token, userId, username, nickname, role, avatar, merchantId }。
 *    前端所有角色渲染只读这份数据，且仅用于「展示」，真正的数据边界
 *    由后端按会话中的角色/商户再次强制校验（前端改了也没用）。
 *
 * 【本次收敛（角色双轨 → 单轨）】
 * 历史上登录态有两条并行轨道：localStorage 直读写 + 本文件的角色判定。
 * 现在「token / userInfo」的唯一权威是 `@/stores/user`（Pinia），
 * 本文件作为**对外稳定门面**：
 *   · pinia 已激活 → 走 stores/user；
 *   · pinia 未激活（router 守卫 / axios 拦截器可能早于 app.use(pinia) 执行）
 *     → 等价回退读 localStorage。
 * ⚠️ 关键：**绝不能裸调 useUserStore()** —— 未激活时会抛
 * `getActivePinia() was called but there was no active Pinia`，
 * 直接让守卫/拦截器中断。统一走 activeUserStore() 判断后回退。
 *
 * 本文件的所有既有导出与语义保持不变，仅内部实现改为「store 优先、localStorage 兜底」，
 * 并新增 getToken / hasUserInfo / isUserInfoBroken / clearAuth 四个收敛用辅助。
 */

import { getActivePinia } from 'pinia'
import { useUserStore } from '@/stores/user'

export const ROLE_ADMIN = 'admin'
export const ROLE_MERCHANT = 'merchant'
export const ROLE_USER = 'user'
/**
 * 【2026-09-28 新增】平台客服角色。
 *
 * 商品退货期限改为商家按商品设置后，超期买家要「先联系客服、由客服依据
 * 商家统一标准裁定」。裁定人必须独立于商家（让被申请方裁决自己是否该退货
 * 中立性不成立），因此单独立了 kefu 角色 —— 与后端 MerchantContext.ROLE_KEFU 一致。
 */
export const ROLE_KEFU = 'kefu'

const TOKEN_KEY = 'token'
const USER_INFO_KEY = 'userInfo'

/**
 * 取得「当前生效」的 user store；pinia 尚未激活时返回 null。
 * 这是「不裸调 useUserStore()」的落点：守卫/拦截器可能在 app.use(pinia)
 * 之前就跑起来，此时必须回退到 localStorage，而不是抛异常。
 */
function activeUserStore() {
  if (!getActivePinia()) return null
  try {
    return useUserStore()
  } catch (e) {
    return null
  }
}

/** 读取 localStorage 里 userInfo 的原始字符串（未激活 store 时的回退路径）。 */
function rawUserInfo() {
  try {
    return localStorage.getItem(USER_INFO_KEY)
  } catch (e) {
    return null
  }
}

/** 容错解析：坏 JSON / 非对象一律返回 {}，绝不抛（P0-6 白屏缺陷防回归）。 */
function parseUserInfo(raw) {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch (e) {
    console.warn('[auth] userInfo 解析失败，按未登录处理', e)
    return {}
  }
}

/**
 * 从 localStorage 读取当前用户信息。解析失败返回空对象而非抛错，
 * 避免 localStorage 被写坏时整个页面白屏。
 *
 * pinia 已激活时以 store 为准（store 初始化时即从 localStorage 载入，二者一致）；
 * 否则回退直接读 localStorage —— 与历史实现语义完全等价。
 */
export function getUserInfo() {
  const store = activeUserStore()
  if (store) {
    const info = store.userInfo
    return info && typeof info === 'object' ? info : {}
  }
  return parseUserInfo(rawUserInfo())
}

/** 当前角色，未登录时返回空字符串 */
export function getRole() {
  return getUserInfo().role || ''
}

export function isAdmin() {
  return getRole() === ROLE_ADMIN
}

export function isMerchant() {
  return getRole() === ROLE_MERCHANT
}

export function isUser() {
  return getRole() === ROLE_USER
}

export function isKefu() {
  return getRole() === ROLE_KEFU
}

/** 当前商家的 merchantId（管理员/普通用户为 null） */
export function getMerchantId() {
  const info = getUserInfo()
  return info.merchantId ?? null
}

/** 角色中文名，用于界面展示 */
export function getRoleLabel(role) {
  const map = {
    [ROLE_ADMIN]: '管理员',
    [ROLE_MERCHANT]: '商家',
    [ROLE_USER]: '普通用户',
    [ROLE_KEFU]: '客服'
  }
  return map[role || getRole()] || '普通用户'
}

/**
 * 首页路径按角色分流。
 *
 * 【区分目标】不同角色登录后应立刻进入与其职责匹配的首页，
 * 而不是三个人都看到同一个「商品橱窗」。
 * - 管理员 → 用户管理（平台运营入口，本项目的管理端主页面）
 * - 商家   → 商家中心 Dashboard（经营数据驾驶舱）
 * - 用户   → 商城首页（购物入口）
 * - 客服   → 客服工作台（超期售后的裁定入口）
 *
 * 注意：管理员与商家虽然都能访问 /home，但 /home 内部也会按角色
 * 渲染完全不同的内容（见 Home.vue + HomeMerchant / HomeUser 组件）。
 */
export function homePathByRole(role) {
  switch (role) {
    case ROLE_ADMIN:
      return '/admin/users'
    case ROLE_MERCHANT:
      return '/merchant'
    case ROLE_KEFU:
      return '/kefu'
    case ROLE_USER:
      return '/home'
    default:
      return '/login'
  }
}

/**
 * 角色主题色，用于首页欢迎区等位置的视觉区分，
 * 让用户一眼看出当前处于哪个角色的工作台。
 */
export function getRoleTheme(role) {
  const themes = {
    [ROLE_ADMIN]: { primary: '#f56c6c', label: '平台管理' },
    [ROLE_MERCHANT]: { primary: '#409eff', label: '商家工作台' },
    [ROLE_USER]: { primary: '#67c23a', label: '会员中心' },
    [ROLE_KEFU]: { primary: '#e6a23c', label: '客服工作台' }
  }
  return themes[role || getRole()] || themes[ROLE_USER]
}

// ==================== 收敛辅助（供 router / request / 组件统一调用） ====================

/** 当前 token（**裸 token**，不含 `Bearer ` 前缀）。未登录返回 ''。 */
export function getToken() {
  const store = activeUserStore()
  if (store) return store.token || ''
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch (e) {
    return ''
  }
}

/** localStorage 里是否存在 userInfo（不判断内容能否解析），与 router 原 `if (!userInfo)` 语义一致。 */
export function hasUserInfo() {
  return !!rawUserInfo()
}

/** userInfo 存在但无法 JSON 解析（被写坏）—— 用于 router 守卫的坏数据清理分支。 */
export function isUserInfoBroken() {
  const raw = rawUserInfo()
  if (!raw) return false
  try {
    JSON.parse(raw)
    return false
  } catch (e) {
    return true
  }
}

/** 清空登录态（登出 / 401 / 改密后登出）：state 与 localStorage 一并清。 */
export function clearAuth() {
  const store = activeUserStore()
  if (store) {
    store.clear()
    return
  }
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_INFO_KEY)
  } catch (e) {
    /* ignore */
  }
}
