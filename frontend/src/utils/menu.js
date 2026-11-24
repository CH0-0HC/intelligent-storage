import { ROLE_ADMIN, ROLE_MERCHANT, ROLE_USER, ROLE_KEFU } from '@/utils/auth'

/**
 * 侧边栏菜单 —— 按角色生成菜单项的唯一数据源
 *
 * 【为什么单独抽出来】菜单原先内联在 Layout.vue 的 computed 里，
 * 只能靠「启动前端点一遍」来验证，任何角色改动都可能悄悄影响另一个角色
 * （例如给管理员加一条菜单时误改到 user 分支）。
 * 抽成纯函数后可以直接单测：入参 role + t，出参菜单数组，不依赖组件挂载。
 *
 * 【角色 → 菜单的职责边界】
 * - user     购物链路：首页 / 商品列表 / 购物车 / 我的订单 / 退款售后 / 我的客服记录
 * - merchant 经营链路：工作台 / 商品 / 库存 / 采购 / 订单 / 售后处理 / AI 看板 / 数据可视化
 * - kefu     只保留客服工作台：客服是平台方角色，职责收在超期售后裁定这一条链路上
 * - admin    平台治理：用户管理 / 日志管理 / 客服工作台
 *
 * 【管理员为什么不挂「我的订单」「售后处理」】
 * 这两条是买家与商家的业务入口：管理员既不为自己下单、也不代商家处理售后，
 * 挂在管理员侧边栏只会让治理型导航混入业务型导航。
 * 注意：这里只约束**侧边栏显示**，路由 /orders 与 /merchant/after-sale
 * 的 meta.roles 仍保留 admin（其他角色要用，且管理员可能从页面内链接进入），
 * 二者是「可见性」与「可访问性」两件事，不要一起改。
 *
 * @param {string} role 角色（utils/auth 的 ROLE_* 常量）
 * @param {(key: string) => string} t i18n 翻译函数
 * @returns {Array<{path: string, title: string, icon: string}>} 菜单项数组
 */
export function buildMenuList(role, t) {
  // 角色判断统一走 @/utils/auth 的常量，避免各处手写字符串比较导致行为不一致
  const baseMenu = [
    { path: '/home', title: t('layout.sidebar.home'), icon: 'HomeFilled' }
  ]

  if (role === ROLE_USER) {
    return [
      ...baseMenu,
      { path: '/products', title: t('layout.sidebar.products'), icon: 'Goods' },
      { path: '/cart', title: t('layout.sidebar.cart'), icon: 'ShoppingCart' },
      { path: '/orders', title: t('layout.sidebar.orders'), icon: 'List' },
      // 【确认收货后申请售后】汇总入口，与订单卡片上的「申请售后」并列，
      // 对齐淘宝/京东「我的」→「退款/售后」的二级入口位置。
      { path: '/after-sale', title: t('layout.sidebar.after_sale'), icon: 'Refresh' },
      // 【2026-09-28】超期售后的客服通道：买家提交的申诉与裁定结果都在这里看，
      // 与「退款/售后」并列 —— 两者是先后阶段，不该互相藏起来。
      { path: '/service-ticket', title: t('layout.sidebar.service_ticket'), icon: 'Service' }
    ]
  } else if (role === ROLE_MERCHANT) {
    return [
      ...baseMenu,
      { path: '/merchant', title: t('layout.sidebar.merchant'), icon: 'DataAnalysis' },
      { path: '/merchant/products', title: t('layout.sidebar.merchant_products'), icon: 'Goods' },
      { path: '/merchant/inventory', title: t('layout.sidebar.merchant_inventory'), icon: 'Box' },
      { path: '/merchant/purchase', title: t('layout.sidebar.merchant_purchase'), icon: 'ShoppingCart' },
      { path: '/merchant/orders', title: t('layout.sidebar.merchant_orders'), icon: 'Document' },
      { path: '/merchant/after-sale', title: t('layout.sidebar.merchant_after_sale'), icon: 'Refresh' },
      { path: '/merchant/ai-analysis', title: t('layout.sidebar.merchant_ai'), icon: 'MagicStick' },
      // 【R16】AI 分析看板（4 个 tab 的完整看板），路由 merchant/ai-dashboard
      { path: '/merchant/ai-dashboard', title: t('layout.sidebar.merchant_ai_dashboard'), icon: 'Opportunity' },
      { path: '/merchant/echarts', title: t('layout.sidebar.merchant_charts'), icon: 'PieChart' }
    ]
  } else if (role === ROLE_KEFU) {
    // 【2026-09-28 新增角色】客服只看自己的工作台：超期售后工单的裁定。
    // 刻意不挂商品/订单/库存等菜单 —— 客服是平台方角色，
    // 职责边界收在 service_ticket 这一条链路上。
    return [
      ...baseMenu,
      { path: '/kefu', title: t('layout.sidebar.kefu'), icon: 'Service' }
    ]
  } else if (role === ROLE_ADMIN) {
    return [
      ...baseMenu,
      { path: '/admin/users', title: t('layout.sidebar.admin_users'), icon: 'User' },
      { path: '/admin/logs', title: t('layout.sidebar.admin_logs'), icon: 'Document' },
      // 管理员可代客服裁定（后端 /service-ticket/manage/* 对 admin 与 kefu 同等开放）
      { path: '/kefu', title: t('layout.sidebar.kefu'), icon: 'Service' }
    ]
  }

  return baseMenu
}
