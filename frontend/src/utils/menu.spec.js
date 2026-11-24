import { describe, it, expect } from 'vitest'
import { buildMenuList } from '@/utils/menu'
import { ROLE_ADMIN, ROLE_MERCHANT, ROLE_USER, ROLE_KEFU } from '@/utils/auth'
import zhCN from '@/i18n/locales/zh-CN.js'
import en from '@/i18n/locales/en.js'

// 用「返回 key 本身」的 t 做断言：与语言无关，且能顺带检查 key 拼写。
const t = (key) => key

/** 菜单项路径列表 */
const paths = (role) => buildMenuList(role, t).map((m) => m.path)
/** 菜单项 i18n key 列表 */
const titleKeys = (role) => buildMenuList(role, t).map((m) => m.title)

const hasKey = (obj, path) =>
  path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj) !== undefined

/**
 * 各角色的完整菜单路径 —— 作为「角色边界」的显式契约。
 * 改动任何一个角色的菜单都必须同时改这里，避免误伤另一个角色而无感知。
 */
const EXPECTED_PATHS = {
  [ROLE_USER]: ['/home', '/products', '/cart', '/orders', '/after-sale', '/service-ticket'],
  [ROLE_MERCHANT]: [
    '/home',
    '/merchant',
    '/merchant/products',
    '/merchant/inventory',
    '/merchant/purchase',
    '/merchant/orders',
    '/merchant/after-sale',
    '/merchant/ai-analysis',
    '/merchant/ai-dashboard',
    '/merchant/echarts'
  ],
  [ROLE_KEFU]: ['/home', '/kefu'],
  // 管理员是平台治理角色：只有用户管理 / 日志管理 / 客服工作台。
  // 刻意不含「我的订单」与「售后处理」—— 见下方专项回归用例。
  [ROLE_ADMIN]: ['/home', '/admin/users', '/admin/logs', '/kefu']
}

describe('buildMenuList —— 侧边栏按角色分流', () => {
  it('四个角色的菜单与契约完全一致（顺序也锁定）', () => {
    for (const [role, expected] of Object.entries(EXPECTED_PATHS)) {
      expect(paths(role), `角色 ${role} 的菜单不一致`).toEqual(expected)
    }
  })

  // ==================== 本次修复的专项回归 ====================
  // 缺陷：管理员登录后侧边栏错误出现「我的订单」「售后处理」。
  // 这两条是买家与商家的业务入口，不属于平台治理型导航。
  describe('管理员侧边栏不含买家/商家的业务入口（防回归）', () => {
    it('不含「我的订单」/orders', () => {
      expect(paths(ROLE_ADMIN)).not.toContain('/orders')
      expect(titleKeys(ROLE_ADMIN)).not.toContain('layout.sidebar.orders')
    })

    it('不含「售后处理」/merchant/after-sale', () => {
      expect(paths(ROLE_ADMIN)).not.toContain('/merchant/after-sale')
      expect(titleKeys(ROLE_ADMIN)).not.toContain('layout.sidebar.merchant_after_sale')
    })

    it('管理员的治理型入口仍然保留（不是把整段菜单删空）', () => {
      expect(paths(ROLE_ADMIN)).toContain('/admin/users')
      expect(paths(ROLE_ADMIN)).toContain('/admin/logs')
      // 管理员可代客服裁定（后端 /service-ticket/manage/* 对 admin 与 kefu 同等开放）
      expect(paths(ROLE_ADMIN)).toContain('/kefu')
      // 所有角色都保留首页
      expect(paths(ROLE_ADMIN)).toContain('/home')
    })
  })

  it('移除仅限管理员：其他角色的入口一个不少', () => {
    // 买家仍然能看到「我的订单」
    expect(paths(ROLE_USER)).toContain('/orders')
    // 商家走的是自己的后台订单页「订单管理」/merchant/orders，
    // 与买家的「我的订单」/orders 是两条不同路由，不受本次改动影响
    expect(paths(ROLE_MERCHANT)).toContain('/merchant/orders')
    // 商家仍然能看到「售后处理」
    expect(paths(ROLE_MERCHANT)).toContain('/merchant/after-sale')
    // 客服的菜单不受影响
    expect(paths(ROLE_KEFU)).toEqual(['/home', '/kefu'])
  })

  // ==================== 通用不变量 ====================

  it('所有角色都以首页开头，且菜单内无重复入口', () => {
    for (const role of [ROLE_USER, ROLE_MERCHANT, ROLE_KEFU, ROLE_ADMIN]) {
      const list = paths(role)
      expect(list[0], `${role} 的第一项应是首页`).toBe('/home')
      expect(new Set(list).size, `${role} 的菜单存在重复路径`).toBe(list.length)
    }
  })

  it('未知 / 空角色只回退到首页，不会漏出任何业务菜单', () => {
    for (const role of [undefined, null, '', 'unknown', 'Admin', 'Merchant']) {
      expect(paths(role)).toEqual(['/home'])
    }
  })

  it('每一项都有 path / title / icon，title 是 i18n key 且中英文都存在', () => {
    const keys = new Set()
    for (const role of [ROLE_USER, ROLE_MERCHANT, ROLE_KEFU, ROLE_ADMIN]) {
      for (const item of buildMenuList(role, t)) {
        expect(typeof item.path).toBe('string')
        expect(item.path.startsWith('/')).toBe(true)
        expect(typeof item.icon).toBe('string')
        expect(item.icon.length).toBeGreaterThan(0)
        // title 必须是 layout.sidebar.* 键，不能是硬编码中文（否则切语言不刷新）
        expect(item.title).toMatch(/^layout\.sidebar\.[a-z_]+$/)
        keys.add(item.title)
      }
    }
    for (const key of keys) {
      expect(hasKey(zhCN, key), `zh-CN 缺少 ${key}`).toBe(true)
      expect(hasKey(en, key), `en 缺少 ${key}`).toBe(true)
    }
  })

  it('被移除的两个入口对应的路由仍然存在且对原角色开放（可见性 ≠ 可访问性）', async () => {
    // 侧边栏只改「是否显示」：/orders 与 /merchant/after-sale 的路由必须保留，
    // 否则商家/买家的现有功能会被一起打断。
    const { default: router } = await import('@/router')
    const byPath = new Map(router.getRoutes().map((r) => [r.path, r]))

    expect(byPath.has('/orders')).toBe(true)
    expect(byPath.has('/merchant/after-sale')).toBe(true)
    expect(byPath.get('/orders').meta.roles).toContain(ROLE_USER)
    expect(byPath.get('/merchant/after-sale').meta.roles).toContain(ROLE_MERCHANT)
  })

  it('菜单里的每个路径都能在路由表中匹配到（不存在死链）', async () => {
    const { default: router } = await import('@/router')
    for (const role of [ROLE_USER, ROLE_MERCHANT, ROLE_KEFU, ROLE_ADMIN]) {
      for (const path of paths(role)) {
        const resolved = router.resolve(path)
        expect(resolved.matched.length, `${role} 的菜单项 ${path} 未匹配到路由`).toBeGreaterThan(0)
      }
    }
  })
})
