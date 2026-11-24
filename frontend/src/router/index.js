import { createRouter, createWebHistory } from 'vue-router'
import NProgress from 'nprogress'
import { homePathByRole, getToken, hasUserInfo, isUserInfoBroken, clearAuth, getUserInfo } from '@/utils/auth'
import i18n from '@/i18n'

// 【i18n】路由标题统一走 titleKey（i18n 键），meta.title 仅作兜底。
// 为什么不直接放翻译结果：routes 是模块级常量，只在 import 时求值一次，
// 之后切换语言不会重新求值 —— 面包屑与浏览器标题会一直停在首次加载的语言。
// 因此这里存「键」，由消费者（Layout 面包屑 / 本文件 beforeEach）在渲染时翻译。
const tr = (key) => i18n.global.t(key)

NProgress.configure({ showSpinner: false })

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/Login.vue'),
    meta: { title: '登录', titleKey: 'login.page_title', requiresAuth: false }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/login/Register.vue'),
    meta: { title: '注册', titleKey: 'register.page_title', requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('@/views/layout/Layout.vue'),
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'Home',
        component: () => import('@/views/home/Home.vue'),
        meta: { title: '首页', titleKey: 'layout.sidebar.home', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      {
        path: 'products',
        name: 'Products',
        component: () => import('@/views/product/ProductList.vue'),
        meta: { title: '商品列表', titleKey: 'layout.sidebar.products', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      {
        path: 'cart',
        name: 'Cart',
        component: () => import('@/views/cart/Cart.vue'),
        meta: { title: '购物车', titleKey: 'layout.sidebar.cart', requiresAuth: true, roles: ['user'] }
      },
      {
        path: 'orders',
        name: 'Orders',
        component: () => import('@/views/order/OrderList.vue'),
        meta: { title: '我的订单', titleKey: 'layout.sidebar.orders', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      // ==================== 售后（确认收货后的退款/退货/换货） ====================
      // 【为什么需要这几条路由】缺陷背景：确认收货（order_status=3）后，
      // 订单列表只剩「再次购买」，页面上没有任何申请售后的入口。
      // 入口在 /orders 的订单卡片操作区，跳转目标就是下面这三条：
      //   /after-sale                  汇总列表（侧边栏「退款/售后」）
      //   /after-sale/apply/:orderId   从订单卡片发起申请
      //   /after-sale/:id              查看进度 / 撤销售后
      {
        path: 'after-sale',
        name: 'AfterSaleList',
        component: () => import('@/views/afterSale/AfterSaleList.vue'),
        meta: { title: '退款/售后', titleKey: 'layout.sidebar.after_sale', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      {
        path: 'after-sale/apply/:orderId',
        name: 'AfterSaleApply',
        component: () => import('@/views/afterSale/AfterSaleApply.vue'),
        meta: { title: '申请售后', titleKey: 'after_sale.entry', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      {
        path: 'after-sale/:id',
        name: 'AfterSaleDetail',
        component: () => import('@/views/afterSale/AfterSaleDetail.vue'),
        meta: { title: '售后详情', titleKey: 'after_sale.detail_title', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      // ==================== 客服对接（超期售后裁定） ====================
      // 【2026-09-28】商品退货期限改为商家按商品设置后，超期买家需「先联系客服、
      // 由客服依据商家统一标准裁定」。这三条是买家侧与客服侧的入口：
      //   /service-ticket             买家：我的客服工单
      //   /service-ticket/apply/:orderId  买家：就某订单发起申诉
      //   /kefu                       客服工作台（裁定台）
      {
        path: 'service-ticket',
        name: 'ServiceTicketList',
        component: () => import('@/views/serviceTicket/ServiceTicketList.vue'),
        meta: { title: '我的客服记录', titleKey: 'service_ticket.my_title', requiresAuth: true, roles: ['user', 'admin', 'kefu'] }
      },
      {
        path: 'service-ticket/apply/:orderId',
        name: 'ServiceTicketApply',
        component: () => import('@/views/serviceTicket/ServiceTicketApply.vue'),
        meta: { title: '联系客服', titleKey: 'service_ticket.apply_title', requiresAuth: true, roles: ['user', 'admin', 'kefu'] }
      },
      {
        path: 'kefu',
        name: 'KefuWorkbench',
        component: () => import('@/views/kefu/KefuWorkbench.vue'),
        meta: { title: '客服工作台', titleKey: 'layout.sidebar.kefu', requiresAuth: true, roles: ['kefu', 'admin'] }
      },
      {
        path: 'kefu/ticket/:id',
        name: 'KefuTicketDetail',
        component: () => import('@/views/kefu/KefuTicketDetail.vue'),
        meta: { title: '工单裁定', titleKey: 'service_ticket.resolve_title', requiresAuth: true, roles: ['kefu', 'admin'] }
      },
      {
        path: 'merchant',
        name: 'MerchantDashboard',
        component: () => import('@/views/merchant/Dashboard.vue'),
        meta: { title: '商家中心', titleKey: 'layout.sidebar.merchant', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'merchant/products',
        name: 'MerchantProducts',
        component: () => import('@/views/merchant/ProductManage.vue'),
        meta: { title: '商品管理', titleKey: 'layout.sidebar.merchant_products', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'merchant/inventory',
        name: 'MerchantInventory',
        component: () => import('@/views/merchant/InventoryManage.vue'),
        meta: { title: '库存管理', titleKey: 'layout.sidebar.merchant_inventory', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'merchant/purchase',
        name: 'MerchantPurchase',
        component: () => import('@/views/merchant/PurchaseManage.vue'),
        meta: { title: '采购管理', titleKey: 'layout.sidebar.merchant_purchase', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'merchant/orders',
        name: 'MerchantOrders',
        component: () => import('@/views/merchant/OrderManage.vue'),
        meta: { title: '订单管理', titleKey: 'layout.sidebar.merchant_orders', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'merchant/after-sale',
        name: 'MerchantAfterSale',
        component: () => import('@/views/merchant/AfterSaleManage.vue'),
        meta: { title: '售后处理', titleKey: 'layout.sidebar.merchant_after_sale', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'merchant/ai-analysis',
        name: 'AIAnalysis',
        component: () => import('@/views/merchant/AIAnalysis.vue'),
        meta: { title: 'AI智能助手', titleKey: 'layout.sidebar.merchant_ai', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        // 【R16】AI 分析看板：AIDashboard.vue 本身即内联实现了四个 tab
        //（库存预警分析 / 销售趋势分析 / 经营报告 / 智能推荐），此前无路由，页面进不去。
        path: 'merchant/ai-dashboard',
        name: 'MerchantAIDashboard',
        component: () => import('@/views/merchant/AIDashboard.vue'),
        meta: { title: 'AI分析看板', titleKey: 'layout.sidebar.merchant_ai_dashboard', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'merchant/echarts',
        name: 'MerchantCharts',
        component: () => import('@/views/merchant/Charts.vue'),
        meta: { title: '数据可视化', titleKey: 'layout.sidebar.merchant_charts', requiresAuth: true, roles: ['admin', 'merchant'] }
      },
      {
        path: 'admin/users',
        name: 'AdminUsers',
        component: () => import('@/views/admin/UserManage.vue'),
        meta: { title: '用户管理', titleKey: 'layout.sidebar.admin_users', requiresAuth: true, roles: ['admin'] }
      },
      {
        path: 'admin/logs',
        name: 'AdminLogs',
        component: () => import('@/views/admin/LogManage.vue'),
        meta: { title: '日志管理', titleKey: 'layout.sidebar.admin_logs', requiresAuth: true, roles: ['admin'] }
      },
      {
        path: 'user/profile',
        name: 'UserProfile',
        component: () => import('@/views/user/Profile.vue'),
        meta: { title: '个人中心', titleKey: 'layout.sidebar.profile', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      {
        path: 'user/password',
        name: 'UserPassword',
        component: () => import('@/views/user/Password.vue'),
        meta: { title: '修改密码', titleKey: 'layout.sidebar.password', requiresAuth: true, roles: ['admin', 'merchant', 'user'] }
      },
      {
        path: 'products/:id',
        name: 'ProductDetail',
        component: () => import('@/views/product/ProductDetail.vue'),
        meta: { title: '商品详情', titleKey: 'product.detail', requiresAuth: false }
      }
    ]
  },
  // 【兜底路由】必须放在路由表最后，用于匹配所有未定义的路径。
  //
  // 为什么要加，两个独立原因：
  // 1) 消除控制台警告 [Vue Router warn]: No match found for location with path。
  //    实测来源**不是本项目代码**（全仓 grep `hybridaction` / `zybTracker` 零命中，
  //    index.html 也没有任何注入脚本）：浏览器插件会在 Vue 安装 router 之前，
  //    用 history.pushState 把地址改成
  //      /hybridaction/zybTrackerStatisticsAction?data=...&__callback__=__cb__...
  //    而 router.install() 内部会执行 push(history.location)，
  //    即把「当前 location.pathname」当成初始路由去 resolve → 匹配不到就 warn。
  //    注意：**不能靠 beforeEach 守卫消除它** —— 警告发生在 pushWithRedirect
  //    内部的 resolve() 阶段，早于守卫执行；只有「存在一条能匹配的路由」才不会再 warn。
  // 2) 用户手输或收藏了错误地址时，不再白屏。
  //
  // 采用 redirect 而不是渲染 404 页：本项目没有 404 视图，且插件注入是高频事件，
  // 落到首页比弹错误页体验更好。将来若要 404 页，把 redirect 换成 component 即可。
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    redirect: '/home'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 把路由标题写进 `<document.title>`。
 *
 * 【2026-10-10 修复 · 切语言后标题不刷新】
 * 原先这段判定内联在 `beforeEach` 里，只有**导航**才会执行。切换语言不触发导航，
 * 于是实测切到 English 后标签页仍是「客服工作台 - 智能进销存系统」，
 * 而 `en.js` 的 `common.app_name` 是 `Smart Inventory System` —— 标题与界面语言脱节。
 * 现抽成导出函数，由导航与语言切换两条路径共用同一份判定，避免两处各写一遍漂移。
 *
 * @param route 目标路由；不传则取 router 当前路由
 */
export function syncDocumentTitle(route) {
  const target = route || router.currentRoute.value
  const appName = tr('common.app_name')
  const pageTitle = target && target.meta && target.meta.titleKey
    ? tr(target.meta.titleKey)
    : (target && target.meta && target.meta.title)
  document.title = pageTitle ? `${pageTitle} - ${appName}` : appName
}

router.beforeEach((to, from, next) => {
  NProgress.start()
  // 【i18n】优先用 titleKey 翻译当前语言；拿不到再退回 meta.title（兜底未知路由）。
  syncDocumentTitle(to)

  // 【收敛】token / userInfo 的读取与清理统一走 @/utils/auth ——
  // 内部「pinia 已激活走 stores/user，未激活回退 localStorage」，
  // 守卫不再直接碰 localStorage（避免登录态双轨）。
  const token = getToken()

  if (!to.meta.requiresAuth) {
    next()
    return
  }

  if (!token) {
    next('/login')
    return
  }

  if (!hasUserInfo()) {
    next('/login')
    return
  }

  // 【健壮性修复】原实现直接 JSON.parse(userInfo)，
  // 一旦 localStorage.userInfo 被写坏（截断、手工篡改、旧版本残留结构），
  // 这里会抛 SyntaxError，整个路由守卫中断 → 页面卡死无响应、
  // 且因为拿不到 role 也不会跳登录页，用户只能手动清缓存。
  // 现改为容错解析，坏数据直接按未登录处理。
  if (isUserInfoBroken()) {
    console.warn('[router] userInfo 解析失败，按未登录处理')
    clearAuth()
    next('/login')
    return
  }
  const role = getUserInfo().role

  // 角色不匹配时，回到「该角色自己的首页」而不是写死 /home。
  // 原实现统一跳 /home，商家/管理员被拦下后会落到不属于自己的工作台视图，
  // 且 /home 内部再按角色二次渲染，等于绕了一圈还可能出现重定向循环。
  if (to.meta.roles && !to.meta.roles.includes(role)) {
    const fallback = homePathByRole(role)
    // 防御：若目标本身就是 fallback，说明配置有环，放行避免死循环
    if (fallback !== to.path && fallback !== '/login') {
      next(fallback)
      return
    }
    next()
    return
  }

  next()
})

router.afterEach(() => {
  NProgress.done()
})

export default router