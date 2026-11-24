import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/user'
import { getToken, getUserInfo, clearAuth, getRole } from '@/utils/auth'

beforeEach(() => {
  localStorage.clear()
  // 每个用例一份全新 pinia，避免 store 实例与 state 在用例间串味。
  setActivePinia(createPinia())
})

describe('stores/user —— 登录态唯一权威', () => {
  it('初始 state 为空（localStorage 无值时）', () => {
    const s = useUserStore()
    expect(s.token).toBe('')
    expect(s.userInfo).toEqual({})
    expect(s.role).toBe('')
    expect(s.merchantId).toBe(null)
    expect(s.nickname).toBe('')
    expect(s.isAdmin).toBe(false)
    expect(s.isMerchant).toBe(false)
    expect(s.isUser).toBe(false)
  })

  it('setLogin 后 state/getters 正确，且已写入 localStorage', () => {
    const s = useUserStore()
    const payload = {
      token: 'tk-1',
      userId: 5,
      username: 'alice',
      nickname: '爱丽丝',
      role: 'merchant',
      merchantId: 9
    }
    s.setLogin(payload)

    expect(s.token).toBe('tk-1')
    expect(s.userInfo).toEqual(payload)
    expect(s.role).toBe('merchant')
    expect(s.merchantId).toBe(9)
    expect(s.isMerchant).toBe(true)
    expect(s.isAdmin).toBe(false)
    expect(s.isUser).toBe(false)
    expect(s.nickname).toBe('爱丽丝')

    // 关键：必须同时落盘，刷新后 store 初始化才能恢复
    expect(localStorage.getItem('token')).toBe('tk-1')
    expect(JSON.parse(localStorage.getItem('userInfo'))).toEqual(payload)
  })

  it('nickname 缺省时回退 username', () => {
    const s = useUserStore()
    s.setLogin({ token: 'tk', username: 'bob', role: 'user' })
    expect(s.nickname).toBe('bob')
  })

  it('clear() 后 state 与 localStorage 的 token/userInfo 均被清空', () => {
    const s = useUserStore()
    s.setLogin({ token: 'tk-2', username: 'root', role: 'admin' })
    s.clear()

    expect(s.token).toBe('')
    expect(s.userInfo).toEqual({})
    expect(s.role).toBe('')
    expect(s.isAdmin).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('userInfo')).toBeNull()
  })

  it('syncFromStorage() 从 localStorage 载入', () => {
    const s = useUserStore()
    localStorage.setItem('token', 'tk-3')
    localStorage.setItem('userInfo', JSON.stringify({ role: 'user', username: 'carol' }))
    s.syncFromStorage()

    expect(s.token).toBe('tk-3')
    expect(s.role).toBe('user')
    expect(s.isUser).toBe(true)
    expect(s.nickname).toBe('carol')
  })

  it('localStorage 里的坏 JSON 不会让 store 初始化抛异常', () => {
    localStorage.setItem('userInfo', '{ 坏数据')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(() => useUserStore()).not.toThrow()
    expect(useUserStore().userInfo).toEqual({})
    warn.mockRestore()
  })

  it('双轨收敛：pinia 激活时 utils/auth 读到的就是 store（写/清同一份状态）', () => {
    const s = useUserStore()
    s.setLogin({ token: 'tk-x', role: 'merchant', merchantId: 3 })

    // auth 门面与 store 必须一致
    expect(getToken()).toBe('tk-x')
    expect(getUserInfo().role).toBe('merchant')
    expect(getRole()).toBe('merchant')

    // 经 auth.clearAuth() 清理，store 也要同步清空（401 / 登出路径）
    clearAuth()
    expect(s.token).toBe('')
    expect(s.userInfo).toEqual({})
    expect(getToken()).toBe('')
    expect(getUserInfo()).toEqual({})
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('userInfo')).toBeNull()
  })
})
