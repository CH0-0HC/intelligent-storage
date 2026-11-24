<template>
  <div class="role-home user-home">
    <!-- ============================================================
         【未付款预警 · 2026-10-10】
         缺陷背景：商家端订单管理页有未付款预警条，用户端却只有「我的订单」里
         一行不显眼的小字 —— 而用户登录后落地的其实是这个首页，第一眼什么都看不到，
         往往等订单被后台任务自动取消后才回来困惑「谁把我的单取消了」。
         这里把提示提到**登录后第一屏的最上方**，并给出最早一笔的剩余时限与
         一键入口，与商家端形成对等（商家能看到的紧迫性，用户自己也应该看到）。
         ============================================================ -->
    <el-alert
      v-if="unpaidCount > 0"
      class="unpaid-home-alert"
      type="warning"
      show-icon
      :closable="false"
    >
      <template #title>{{ t('order.unpaid_alert', { count: unpaidCount }) }}</template>
      <template #default>
        <div class="unpaid-home-body">
          <span class="unpaid-home-desc">{{ unpaidAlertDesc }}</span>
          <el-button type="warning" size="small" @click="router.push('/orders')">
            {{ t('order.go_pay') }}
          </el-button>
        </div>
      </template>
    </el-alert>

    <!-- 角色标识条 -->
    <div class="role-banner user-banner">
      <div class="role-banner-left">
        <el-icon class="role-banner-icon"><ShoppingBag /></el-icon>
        <div>
          <div class="role-banner-title">{{ t('home.role_home_page.user_banner_title') }}</div>
          <div class="role-banner-desc">{{ t('home.role_home_page.user_banner_desc') }}</div>
        </div>
      </div>
      <el-button type="primary" round @click="router.push('/products')">
        <el-icon><Goods /></el-icon> {{ t('home.role_home_page.user_go_shopping') }}
      </el-button>
    </div>

    <!-- 我的数据 -->
    <div class="stats-section">
      <div class="section-header"><h3>{{ t('home.role_home_page.user_overview') }}</h3></div>
      <div class="stats-grid">
        <div
          v-for="(stat, index) in userStats"
          :key="stat.title"
          class="stat-card"
          :class="{ 'card-clickable': stat.link }"
          :style="{ '--card-delay': `${index * 0.08}s`, '--card-color': stat.color }"
          @click="stat.link && router.push(stat.link)"
        >
          <div class="stat-content">
            <el-icon :size="40" :color="stat.color" class="stat-icon">
              <component :is="stat.icon" />
            </el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stat.value }}</div>
              <div class="stat-title">{{ stat.title }}</div>
            </div>
          </div>
          <div class="stat-footer" v-if="stat.footer">
            <span class="stat-footer-text">{{ stat.footer }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 我的订单入口 -->
    <el-card class="section-card" shadow="hover">
      <template #header>
        <div class="section-header">
          <h3 class="section-title"><el-icon><List /></el-icon> {{ t('home.role_home_page.user_my_orders') }}</h3>
          <el-button type="primary" text @click="router.push('/orders')">{{ t('home.role_home_page.user_view_all') }}</el-button>
        </div>
      </template>
      <div class="order-status-grid">
        <div
          v-for="s in orderStatusEntries"
          :key="s.title"
          class="order-status-item"
          @click="router.push(s.link)"
        >
          <el-icon :size="26" :color="s.color"><component :is="s.icon" /></el-icon>
          <div class="order-status-count">{{ s.value }}</div>
          <div class="order-status-title">{{ s.title }}</div>
        </div>
      </div>
    </el-card>

    <!-- 热门商品（复用原有商品卡片样式，保持视觉延续） -->
    <el-card class="section-card" shadow="hover">
      <template #header>
        <div class="section-header">
          <h3 class="section-title"><el-icon><Star /></el-icon> {{ t('home.role_home_page.user_hot_products') }}</h3>
          <el-button type="primary" text @click="router.push('/products')">{{ t('home.role_home_page.user_all_products') }}</el-button>
        </div>
      </template>

      <el-skeleton v-if="loadingProducts" :rows="2" animated />
      <el-empty v-else-if="hotProducts.length === 0" :description="t('home.role_home_page.user_no_hot_products')" />
      <div v-else class="product-grid">
        <div
          v-for="product in hotProducts"
          :key="product.id"
          class="product-item"
          @click="router.push(`/products/${product.id}`)"
        >
          <div class="product-image-container">
            <img
              :src="getImageUrl(product.image)"
              class="product-image"
              :alt="product.name"
              loading="lazy"
              @error="onImgError"
            />
            <div v-if="product.stock <= 10" class="stock-badge low-stock">{{ t('home.role_home_page.user_low_stock') }}</div>
          </div>
          <div class="product-info">
            <div class="product-name" :title="product.name">{{ product.name }}</div>
            <div class="product-meta">
              <span>{{ t('home.role_home_page.user_sales', { count: product.sales ?? 0 }) }}</span>
            </div>
            <div class="product-price">¥{{ Number(product.price || 0).toFixed(2) }}</div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  ShoppingBag, Goods, List, Star,
  Wallet, Van, ShoppingCart, Timer, CircleCheck, CircleClose
} from '@element-plus/icons-vue'
import { getHotProducts } from '@/api/product'
import { getOrderList } from '@/api/order'
import { getCartList } from '@/api/cart'
import { getImageUrl } from '@/utils/image'
import { getUserInfo } from '@/utils/auth'
import { useNowTicker, remainingMs, formatRemaining } from '@/utils/payCountdown'

const router = useRouter()
const { t } = useI18n()
const userInfo = computed(() => getUserInfo())

const loadingProducts = ref(true)
const hotProducts = ref([])
const orderCounts = ref({ pending: 0, shipping: 0, done: 0, canceled: 0, cart: 0, total: 0, amount: 0 })

// 当前用户的原始订单列表（loadMyData 拉一次，统计与未付款预警共用这一份数据，
// 不再为了预警多打一次接口）
const myOrders = ref([])

// 每秒心跳：让首页的未付款倒计时也跟着走，而不是停在进入页面那一刻的快照上
const now = useNowTicker()

// 「等待付款」= 待付款 且 未付款。与「我的订单」页同一口径：
// 只认后端 payStatus，不用 orderStatus 反推（状态 1 也可能来自商家直接发货）。
const awaitingPayOrders = computed(() =>
  myOrders.value.filter(o => Number(o.orderStatus) === 0 && Number(o.payStatus) !== 1)
)
const unpaidCount = computed(() => awaitingPayOrders.value.length)

// 最早一笔的剩余时间（毫秒）。没有 payDeadline 的订单不参与比较 ——
// 超时时长只由后端定义，前端不自己推算。
const soonestRemainingMs = computed(() => {
  const list = awaitingPayOrders.value
    .map(o => remainingMs(o.payDeadline, now.value))
    .filter(ms => ms !== null)
  return list.length ? Math.min(...list) : null
})

// 预警条文案：剩多久 / 已到点 / 拿不到时限时的通用兜底，三种情况都说得清楚
const unpaidAlertDesc = computed(() => {
  const ms = soonestRemainingMs.value
  if (ms === null) return t('order.unpaid_alert_desc_no_deadline')
  if (ms <= 0) return t('order.unpaid_alert_desc_expired')
  return t('order.unpaid_alert_desc', { remaining: formatRemaining(ms) })
})

// 「待付款」统计卡片的脚注：有未付款单时把它变成倒计时提醒。
// 这张卡片本来只是一句静态说明，用户扫过去不会停下；换成会跳动的剩余时间后，
// 它就同时承担了「有几个待付款」和「还剩多久」两个信息。
const pendingFooter = computed(() => {
  if (unpaidCount.value === 0) return t('home.role_home_page.user_pending_footer')
  const ms = soonestRemainingMs.value
  if (ms === null) return t('home.role_home_page.user_pending_footer_urgent')
  if (ms <= 0) return t('home.role_home_page.user_pending_footer_urgent')
  return t('home.role_home_page.user_pending_footer_countdown', { remaining: formatRemaining(ms) })
})

// 用户侧统计：全部来自后端真实数据，不再使用硬编码演示值
const userStats = computed(() => [
  { title: t('home.role_home_page.user_total_orders'), value: `${orderCounts.value.total}`, icon: List, color: '#409eff', footer: t('home.role_home_page.user_total_orders_footer'), link: '/orders' },
  { title: t('home.role_home_page.user_pending'), value: `${orderCounts.value.pending}`, icon: Timer, color: '#e6a23c', footer: pendingFooter.value, link: '/orders' },
  { title: t('home.role_home_page.user_shipping'), value: `${orderCounts.value.shipping}`, icon: Van, color: '#67c23a', footer: t('home.role_home_page.user_shipping_footer'), link: '/orders' },
  { title: t('home.role_home_page.user_cart'), value: `${orderCounts.value.cart}`, icon: ShoppingCart, color: '#f56c6c', footer: t('home.role_home_page.user_cart_footer'), link: '/cart' }
])

/**
 * 「我的订单」五个状态格。
 *
 * 【2026-10-10 修复 · 两点一起改】
 * ① 原本只有 icon + 标题，**没有任何计数**（数字全在上面四张卡片里），
 *    五格看起来像五个等权按钮，用户扫不出「哪一类有单子」；
 * ② `link` 一律是 `/orders` —— 点「已取消」进去看到的是「全部订单」，
 *    与格子上的数字对不上（同时 `/orders` 当时也确实没有「已取消」页签）。
 * 现在每格带 `?status=N` 深链，`OrderList` 会把它落成对应页签。
 */
const orderStatusEntries = computed(() => [
  { title: t('home.role_home_page.user_all_orders'), value: orderCounts.value.total, link: '/orders', icon: List, color: '#409eff' },
  { title: t('home.role_home_page.user_pending'), value: orderCounts.value.pending, link: '/orders?status=0', icon: Timer, color: '#e6a23c' },
  { title: t('home.role_home_page.user_shipping'), value: orderCounts.value.shipping, link: '/orders?status=2', icon: Van, color: '#67c23a' },
  { title: t('home.role_home_page.user_done'), value: orderCounts.value.done, link: '/orders?status=3', icon: CircleCheck, color: '#67c23a' },
  { title: t('home.role_home_page.user_canceled'), value: orderCounts.value.canceled, link: '/orders?status=4', icon: CircleClose, color: '#909399' }
])

const onImgError = (e) => {
  e.target.src = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyMDAiIGhlaWdodD0iMTQwIiB2aWV3Qm94PSIwIDAgMjAwIDE0MCI+PHJlY3Qgd2lkdGg9IjIwMCIgaGVpZ2h0PSIxNDAiIGZpbGw9IiNmNWY3ZmEiLz48dGV4dCB4PSIxMDAiIHk9Ijc1IiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBmaWxsPSIjOTA5Mzk5IiBmb250LXNpemU9IjEyIj5ObyBJbWFnZTwvdGV4dD48L3N2Zz4='
}

const loadHotProducts = async () => {
  loadingProducts.value = true
  try {
    const res = await getHotProducts(8)
    hotProducts.value = res?.data ?? res ?? []
  } catch (e) {
    console.error('加载热门商品失败', e)
  } finally {
    loadingProducts.value = false
  }
}

/**
 * 拉取当前用户的真实订单与购物车数据，替换掉原先写死的演示数字。
 * 订单状态映射（与后端 order.order_status 约定一致）：
 *   0 待付款 / 1 待发货 / 2 待收货 / 3 已完成 / 4 已取消
 */
const loadMyData = async () => {
  try {
    const res = await getOrderList({ page: 1, size: 200 })
    const payload = res?.data ?? res ?? {}
    const list = payload.records ?? payload.list ?? (Array.isArray(payload) ? payload : [])
    // 原始列表另外留存：统计数字与未付款预警（含 payDeadline 倒计时）共用同一份数据
    myOrders.value = Array.isArray(list) ? list : []
    orderCounts.value = {
      total: payload.total ?? list.length,
      pending: list.filter(o => o.orderStatus === 0).length,
      shipping: list.filter(o => o.orderStatus === 2).length,
      done: list.filter(o => o.orderStatus === 3).length,
      // 【2026-10-10】补已取消计数：状态格里有「已取消」这一项，此前却没有任何地方统计它
      canceled: list.filter(o => o.orderStatus === 4).length,
      cart: orderCounts.value.cart,
      amount: list
        .filter(o => o.payStatus === 1)
        .reduce((sum, o) => sum + Number(o.payAmount || 0), 0)
    }
  } catch (e) {
    console.error('加载我的订单失败', e)
  }

  try {
    const res = await getCartList()
    const list = res?.data ?? res ?? []
    orderCounts.value = { ...orderCounts.value, cart: Array.isArray(list) ? list.length : 0 }
  } catch (e) {
    console.error('加载购物车失败', e)
  }
}

// ==================== 未付款期间的低频轮询 ====================
//
// 用户可能在另一个标签页 / 另一台设备上完成付款，首页若不刷新就会一直挂着
// 一条已经过期的预警（并且倒计时继续走向 0）。因此只在「确实还有未付款订单」时
// 每 30 秒重拉一次，付清后条件不成立、轮询自动停止，不给正常浏览白打请求。
// 这里复用 loadMyData（顺带刷新购物车计数），不为轮询单开一个精简请求。
let unpaidPollTimer = null
watch(unpaidCount, (count) => {
  if (count > 0 && !unpaidPollTimer) {
    unpaidPollTimer = setInterval(loadMyData, 30000)
  } else if (count === 0 && unpaidPollTimer) {
    clearInterval(unpaidPollTimer)
    unpaidPollTimer = null
  }
})

onBeforeUnmount(() => {
  if (unpaidPollTimer) {
    clearInterval(unpaidPollTimer)
    unpaidPollTimer = null
  }
})

onMounted(() => {
  loadHotProducts()
  loadMyData()
})
</script>

<style scoped>
.role-home { display: flex; flex-direction: column; gap: 20px; }

/* ===== 未付款预警（登录后第一屏） ===== */
.unpaid-home-alert { border-radius: 12px; }
.unpaid-home-body { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.unpaid-home-desc { font-size: 13px; }

.role-banner {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 24px; border-radius: 16px;
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
  /* 去掉嵌套 backdrop-filter（见 glass.scss 性能说明） */
  background-image: var(--glass-sheen-gradient);
}
.user-banner {
  background: linear-gradient(135deg, rgba(103,194,58,0.16), rgba(103,194,58,0.03));
  border-left: 4px solid #67c23a;
}
.role-banner-left { display: flex; align-items: center; gap: 14px; }
.role-banner-icon { font-size: 30px; color: #67c23a; }
.role-banner-title { font-size: 18px; font-weight: 600; color: var(--glass-text-primary, #e8e8e8); }
.role-banner-desc { font-size: 13px; color: var(--glass-text-secondary, #a0a0a8); margin-top: 2px; }

.stats-section { display: flex; flex-direction: column; gap: 12px; }
.section-header { display: flex; align-items: center; justify-content: space-between; }
.section-header h3 { margin: 0; font-size: 17px; color: var(--glass-text-primary, #e8e8e8); }
.section-title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 16px; color: var(--glass-text-primary, #e8e8e8); }

.stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; }
.stat-card {
  position: relative; padding: 20px; border-radius: 16px;
  background: var(--glass-bg, rgba(255,255,255,0.06));
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
  overflow: hidden; animation: cardIn .4s ease both; animation-delay: var(--card-delay, 0s);
}
.stat-card::before {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px;
  background: var(--card-color, #409eff); opacity: .7;
}
.card-clickable { cursor: pointer; transition: transform .2s ease, box-shadow .2s ease; }
.card-clickable:hover { transform: translateY(-3px); box-shadow: 0 12px 28px rgba(0,0,0,.22); }
.stat-content { display: flex; align-items: center; gap: 16px; }
.stat-info { flex: 1; }
.stat-value { font-size: 26px; font-weight: 700; color: var(--glass-text-primary, #e8e8e8); line-height: 1.2; }
.stat-title { font-size: 14px; color: var(--glass-text-secondary, #a0a0a8); margin-top: 4px; }
.stat-footer { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--glass-border-light, rgba(255,255,255,0.08)); }
.stat-footer-text { font-size: 12px; color: var(--glass-text-muted, #8a8a92); }

.section-card { border-radius: 18px; background: var(--glass-bg, rgba(255,255,255,0.06)); border: 1px solid var(--glass-border, rgba(255,255,255,0.12)); }

.order-status-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 12px; }
.order-status-item {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 16px 10px; border-radius: 12px; cursor: pointer;
  border: 1px solid var(--glass-border-light, rgba(255,255,255,0.08));
  transition: all .2s ease;
}
.order-status-item:hover { background: rgba(103,194,58,0.08); transform: translateY(-2px); }
.order-status-title { font-size: 13px; color: var(--glass-text-secondary, #a0a0a8); }
/* 【2026-10-10】状态格补的计数：压在标题上方、比标题重，扫一眼就能看出哪一类有单子 */
.order-status-count {
  font-size: 20px; font-weight: 600; line-height: 1;
  color: var(--glass-text-primary, #e8e8f0);
}

.product-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 18px; }
.product-item {
  border-radius: 14px; overflow: hidden; cursor: pointer;
  background: var(--glass-bg, rgba(255,255,255,0.06));
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
  transition: transform .2s ease, box-shadow .2s ease;
}
.product-item:hover { transform: translateY(-4px); box-shadow: 0 14px 30px rgba(0,0,0,.25); }
.product-image-container { position: relative; height: 140px; overflow: hidden; background: var(--glass-bg-dark, rgba(255,255,255,0.04)); }
.product-image { width: 100%; height: 100%; object-fit: cover; transition: transform .5s ease; }
.product-item:hover .product-image { transform: scale(1.08); }
.stock-badge { position: absolute; top: 8px; right: 8px; padding: 2px 8px; border-radius: 4px; font-size: 12px; color: #fff; }
.low-stock { background: rgba(245,108,108,.9); }
.product-info { padding: 14px; }
.product-name {
  font-size: 14px; font-weight: 600; height: 40px; overflow: hidden;
  color: var(--glass-text-primary, #e8e8e8);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.product-meta { font-size: 12px; color: var(--glass-text-muted, #8a8a92); margin: 8px 0; }
.product-price { font-size: 19px; font-weight: 700; color: #ff3b30; }

@keyframes cardIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
