import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import i18n from '@/i18n'
import { getToken, clearAuth } from '@/utils/auth'

// 【i18n】本模块是纯 JS（无组件上下文），走 i18n 实例全局 t()。
// 提示文案在「请求发生的那一刻」求值 —— 切换语言后新产生的提示会跟随语言。
const t = (key) => i18n.global.t(key)

/**
 * 构造鉴权请求头 —— **全站唯一出口**。
 *
 * 【为什么必须收敛到一处】
 * 后端用的是 Sa-Token，而 `application.yml` 里**没有**配置
 * `sa-token.token-prefix`。这意味着 Authorization 头里必须放**裸 token**
 * （本项目 token-style = uuid，形如 985c066b-39d1-4f15-a515-c51fc23857d3）。
 *
 * 一旦有哪处画蛇添足加上 `Bearer ` 前缀，Sa-Token 会把整串
 * `Bearer <uuid>` 当成 token 值去查会话，自然查不到 → 401。
 * 实测对照（同一 token、同一接口）：
 *   Authorization: <uuid>          → 200
 *   Authorization: Bearer <uuid>   → 401
 *
 * 【历史坑】ProductManage.vue 的 `el-upload` 曾经自己拼了一套
 * `'Bearer ' + localStorage.getItem('token') || ''`，两处规则不一致，
 * 表现为「商家登录后上传商品图片 401」——同一个已登录会话，
 * axios 请求正常、上传失败。el-upload 走的是它自己的 XHR，不经过本文件的
 * 拦截器，所以必须显式 :headers，且必须用这里的返回值。
 * 另注：原写法还有运算符优先级问题，`'a' + x || ''` 等价于 `('a' + x) || ''`，
 * token 为 null 时会得到真值字符串 "Bearer null"，兜底永远不会生效。
 *
 * @returns {{ Authorization: string } | {}} 无 token 时返回空对象
 */
export function buildAuthHeader() {
  // 【收敛】token 统一经 @/utils/auth 读取（内部 store 优先、localStorage 兜底），
  // 但**必须仍是裸 token**：Sa-Token 未配置 token-prefix，加 `Bearer ` 必 401。
  const token = getToken()
  return token ? { Authorization: token } : {}
}

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

/**
 * 统一处理「登录态失效」。
 *
 * 【两个曾经的真实问题】
 * 1. 只清 token、**不清 userInfo** —— 留下半截登录态。userInfo 里带着 role，
 *    而路由守卫是「先看 token 再看 userInfo」，于是会短暂出现
 *    「token 没了但 userInfo 还在」的不一致状态。
 * 2. 一个页面往往同时发多个请求，一旦登录态失效会**连发 N 个 401**：
 *    原来的写法每次都弹一遍「登录已过期」并 push 一次 /login，
 *    用户看到一串重复弹窗。
 *
 * 这里用「还有没有 token 可清」来判定是否首次：首次才提示 + 跳转，
 * 后续 401 静默处理；重新登录后 token 回来了，逻辑自然恢复。
 */
function handleSessionExpired(message) {
  // 【收敛】token/userInfo 的清理统一走 clearAuth()（内部同步清 state + localStorage）。
  // 「还有没有 token 可清」仍用于判定是否首次 401：首次才提示 + 跳转。
  const hadToken = !!getToken()
  clearAuth()
  if (!hadToken) {
    return
  }
  ElMessage.error(message)
  if (router.currentRoute.value.path !== '/login') {
    router.push('/login')
  }
}

request.interceptors.request.use(
  config => {
    Object.assign(config.headers, buildAuthHeader())
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      // 401 交给统一的失效处理，不再额外弹一次原始 message
      if (res.code === 401) {
        handleSessionExpired(res.message || t('common.session_expired'))
        return Promise.reject(new Error(res.message || t('common.session_expired')))
      }
      ElMessage.error(res.message || t('common.request_failed'))
      return Promise.reject(new Error(res.message || t('common.request_failed')))
    }
    return res
  },
  error => {
    if (error.response) {
      if (error.response.status === 401) {
        handleSessionExpired(t('common.session_expired'))
      } else {
        ElMessage.error(error.response.data?.message || t('common.request_failed'))
      }
    } else {
      ElMessage.error(t('common.network_error'))
    }
    return Promise.reject(error)
  }
)

export default request
