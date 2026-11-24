import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  getUserInfo,
  getRole,
  isAdmin,
  isMerchant,
  isUser,
  getMerchantId,
  getRoleLabel,
  homePathByRole,
  getRoleTheme,
  ROLE_ADMIN,
  ROLE_MERCHANT,
  ROLE_USER
} from '@/utils/auth'

// 本文件刻意**不激活 pinia**：此时 auth.js 必须走 localStorage 回退路径，
// 这正是 router 守卫 / axios 拦截器「早于 app.use(pinia) 执行」的真实场景。
beforeEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('getUserInfo 容错（不白屏）', () => {
  it('localStorage 无值时返回 {}', () => {
    expect(getUserInfo()).toEqual({})
    expect(getRole()).toBe('')
  })

  it('值是坏 JSON 时返回 {} 且不抛（P0-6 防回归）', () => {
    localStorage.setItem('userInfo', '{ 这不是 JSON')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(() => getUserInfo()).not.toThrow()
    expect(getUserInfo()).toEqual({})
    expect(getRole()).toBe('')
    warn.mockRestore()
  })

  it('值是合法 JSON 但非对象（字符串/数字）时返回 {}', () => {
    localStorage.setItem('userInfo', JSON.stringify('abc'))
    expect(getUserInfo()).toEqual({})
    localStorage.setItem('userInfo', JSON.stringify(123))
    expect(getUserInfo()).toEqual({})
  })
})

describe('角色判定', () => {
  it('admin', () => {
    localStorage.setItem('userInfo', JSON.stringify({ role: ROLE_ADMIN }))
    expect(getRole()).toBe('admin')
    expect(isAdmin()).toBe(true)
    expect(isMerchant()).toBe(false)
    expect(isUser()).toBe(false)
    expect(getMerchantId()).toBe(null)
    expect(getRoleLabel()).toBe('管理员')
  })

  it('merchant（含 merchantId）', () => {
    localStorage.setItem('userInfo', JSON.stringify({ role: ROLE_MERCHANT, merchantId: 7 }))
    expect(isMerchant()).toBe(true)
    expect(isAdmin()).toBe(false)
    expect(isUser()).toBe(false)
    expect(getMerchantId()).toBe(7)
    expect(getRoleLabel()).toBe('商家')
  })

  it('user', () => {
    localStorage.setItem('userInfo', JSON.stringify({ role: ROLE_USER }))
    expect(isUser()).toBe(true)
    expect(isAdmin()).toBe(false)
    expect(isMerchant()).toBe(false)
    expect(getMerchantId()).toBe(null)
  })

  it('未登录时四个判定全为 false', () => {
    expect(isAdmin()).toBe(false)
    expect(isMerchant()).toBe(false)
    expect(isUser()).toBe(false)
  })
})

describe('homePathByRole 三分支 + 默认', () => {
  it('admin/merchant/user/其它', () => {
    expect(homePathByRole(ROLE_ADMIN)).toBe('/admin/users')
    expect(homePathByRole(ROLE_MERCHANT)).toBe('/merchant')
    expect(homePathByRole(ROLE_USER)).toBe('/home')
    expect(homePathByRole('')).toBe('/login')
    expect(homePathByRole(undefined)).toBe('/login')
    expect(homePathByRole('seller')).toBe('/login')
  })
})

describe('getRoleTheme 回退', () => {
  it('已知角色返回各自主题，未知回退 user 主题', () => {
    expect(getRoleTheme(ROLE_ADMIN).primary).toBe('#f56c6c')
    expect(getRoleTheme(ROLE_MERCHANT).primary).toBe('#409eff')
    expect(getRoleTheme(ROLE_USER).primary).toBe('#67c23a')
    expect(getRoleTheme('nope')).toEqual(getRoleTheme(ROLE_USER))
  })

  it('不传参时按当前角色取，未登录回退 user 主题', () => {
    expect(getRoleTheme()).toEqual(getRoleTheme(ROLE_USER))
    localStorage.setItem('userInfo', JSON.stringify({ role: ROLE_ADMIN }))
    expect(getRoleTheme()).toEqual(getRoleTheme(ROLE_ADMIN))
  })
})
