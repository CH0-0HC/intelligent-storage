<template>
  <div class="order-list-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('order.list') }}</span>
          <el-button type="primary" size="small" @click="refreshOrders">
            <el-icon>Refresh</el-icon>
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>
      
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane :label="t('order.all_orders')" name="all" />
        <el-tab-pane :label="t('order.status_pending')" name="0" />
        <el-tab-pane :label="t('order.status_paid')" name="1" />
        <el-tab-pane :label="t('order.status_shipped')" name="2" />
        <el-tab-pane :label="t('order.status_completed')" name="3" />
        <!-- 【2026-10-10 修复】首页「我的订单」状态格里有「已取消」，入口指向本页，
             但这里一直没有对应页签 —— 点进去看到的是「全部订单」，
             用户会以为取消的单子不见了。复用既有的 order.status_canceled 键（不新增 i18n）。 -->
        <el-tab-pane :label="t('order.status_canceled')" name="4" />
      </el-tabs>

      <!-- ============================================================
           【未付款预警 · 2026-10-10】
           缺陷背景：商家端已有未付款预警条与倒计时，用户端却只有卡片底部一行
           12px 灰字 —— 两端信息权重严重不对称。用户在「我的订单」里看不出
           「这单有时限、不付款会被收走」，等订单被后台任务取消后才回来困惑。
           这里把同一件事提到页面最上方：未付款单数 + 最早一笔的剩余时限 +
           一键跳转付款，与商家端形成对等提示。

           【为什么按当前页签的数据统计】列表在非「全部」页签下是**服务端按状态
           过滤**的（loadOrders 会带 status 参数），所以这里只能用已加载的
           orders 统计，而不是再发一次全量请求。默认页签是「全部」，
           用户第一眼的判断条件成立；若他正停在「待收货」页签，这里不显示
           待付款提醒也是合理的（他此刻关心的不是付款）。
           ============================================================ -->
      <el-alert
        v-if="!loading && awaitingPayOrders.length > 0"
        class="pay-alert"
        type="warning"
        show-icon
        :closable="false"
      >
        <template #title>{{ t('order.unpaid_alert', { count: awaitingPayOrders.length }) }}</template>
        <template #default>
          <div class="pay-alert-body">
            <span class="pay-alert-desc">{{ alertDesc }}</span>
            <el-button type="warning" size="small" @click="goPaySoonest">{{ t('order.go_pay') }}</el-button>
          </div>
        </template>
      </el-alert>


      <div v-if="loading" class="loading-state">
        <el-icon class="is-loading" :size="28"><Loading /></el-icon>
        <p>{{ t('order.loading') }}</p>
      </div>
      
      <div v-else-if="filteredOrders.length === 0" class="empty-state">
        <el-icon :size="48" class="empty-icon">ShoppingBag</el-icon>
        <p>{{ t('order.no_orders') }}{{ currentTabName }}{{ t('order.order') }}</p>
      </div>
      
      <div v-else class="order-list">
        <!-- :id 是「直接购买下单后跳转过来」定位新订单的锚点（见 highlightOrder） -->
        <div
          v-for="order in filteredOrders"
          :key="order.id"
          :id="'order-' + order.id"
          class="order-card"
          :class="{ 'order-card-highlight': highlightedOrderId === order.id }"
        >
          <div class="order-header">
            <div class="order-no">
              {{ t('order.order_no') }}：{{ order.orderNo }}
              <el-tag
                v-if="order.afterSale && order.afterSale.hasAfterSale"
                size="small"
                type="warning"
                class="sale-tag"
              >
                {{ t('after_sale.title') }}
              </el-tag>
            </div>
            <div class="order-time">{{ formatDateTime(order.createTime) }}</div>
          </div>
          
          <div class="order-items">
            <div v-for="item in order.items" :key="item.id" class="order-item">
              <img :src="getImageUrl(item.productImage)" class="item-image" @error="($event.target.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI2MCIgaGVpZ2h0PSI2MCIgdmlld0JveD0iMCAwIDYwIDYwIj48cmVjdCB3aWR0aD0iNjAiIGhlaWdodD0iNjAiIGZpbGw9IiNmNWY3ZmEiIHJ4PSIzIi8+PHRleHQgeD0iMzAiIHk9IjM1IiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBmaWxsPSIjOTA5MzkiIGZvbnQtc2l6ZT0iOSIgZm9udC1mYW1pbHk9IkFyaWFsIj5ObyBJbWFnZTwvdGV4dD48L3N2Zz4=')" />
              <div class="item-info">
                <div class="item-name">{{ item.productName }}</div>
                <div class="item-spec">{{ item.spec || t('order.spec_default') }}</div>
              </div>
              <div class="item-price">¥{{ item.price }}</div>
              <div class="item-quantity">x{{ item.quantity }}</div>
            </div>
          </div>
          
          <!-- ============================================================
               【卡片内未付款倒计时条 · 2026-10-10】
               原来是卡片底部状态标签旁的一行 12px 灰字，实测中用户根本没注意到。
               这里升级成卡片内独立的整行提示条 —— 图标 + 明确文案 + 剩余时间，
               并且用警示底色把它从「商品清单 / 金额」里拎出来。

               倒计时归零后不是简单地把文案留在 00:00：后台任务是按周期扫描的，
               归零与真正取消之间有最长约一分钟的窗口，此时改用「正在自动取消」
               文案（见 payBannerText），避免用户盯着 00:00 以为页面卡死。
               ============================================================ -->
          <div
            v-if="isAwaitingPay(order)"
            class="unpaid-banner"
            :class="{ 'unpaid-banner-expired': isDeadlinePassed(order) }"
          >
            <el-icon class="unpaid-banner-icon"><Clock /></el-icon>
            <span class="unpaid-banner-text">{{ payBannerText(order) }}</span>
            <el-button type="danger" size="small" class="unpaid-banner-btn" @click="payOrder(order)" :loading="payingOrderId === order.id">
              {{ t('order.pay') }}
            </el-button>
          </div>

          <div class="order-footer">
            <div class="order-status">
              <el-tag :type="getStatusType(order.orderStatus)">
                {{ getStatusText(order.orderStatus) }}
              </el-tag>
              <!-- 已取消订单的取消原因。待付款的倒计时已经上移到卡片内的提示条，
                   这里只保留「为什么被取消」这一种情况，避免同一信息出现两遍。 -->
              <div v-if="cancelReasonText(order)" class="pay-hint">
                {{ cancelReasonText(order) }}
              </div>
            </div>
            <div class="order-total">
              <span>{{ t('order.total_items') }}{{ getTotalQuantity(order) }}{{ t('order.items') }}</span>
              <span class="total-amount">{{ t('order.pay_amount') }}：<span class="amount">¥{{ order.payAmount }}</span></span>
            </div>
          </div>
          
          <div class="order-actions">
            <!-- 「立即付款」的主入口已上移到卡片内的未付款提示条。
                 这里只在**提示条没有渲染**的极端情况下兜底（isAwaitingPay 为假
                 但订单状态仍是待付款），保证付款入口在任何分支下都不会消失。 -->
            <el-button 
              v-if="order.orderStatus === 0 && !isAwaitingPay(order)" 
              type="primary" 
              size="small"
              @click="payOrder(order)"
              :loading="payingOrderId === order.id"
            >
              {{ t('order.pay') }}
            </el-button>
            <el-button 
              v-if="order.orderStatus === 0" 
              type="primary" link 
              size="small"
              @click="cancelOrder(order)"
            >
              {{ t('order.cancel') }}
            </el-button>
            <el-button 
              v-if="order.orderStatus === 1" 
              type="primary" link 
              size="small"
              disabled
            >
              {{ t('order.waiting_ship') }}
            </el-button>
            <el-button 
              v-if="order.orderStatus === 2" 
              type="success" 
              size="small"
              @click="confirmReceive(order)"
              :loading="receivingOrderId === order.id"
            >
              {{ t('order.confirm_receipt') }}
            </el-button>
            <el-button 
              v-if="order.orderStatus === 3" 
              type="primary" link 
              size="small"
              @click="reorder(order)"
            >
              {{ t('order.reorder') }}
            </el-button>

            <!-- ============================================================
                 【缺陷修复 · 确认收货后申请售后】
                 入口位置对齐淘宝/京东：「我的订单」订单卡片底部操作区，
                 与「再次购买」并列（不做成独立页面，避免用户找不到）。

                 展示条件**完全由后端裁定**（OrderList 接口返回的 order.afterSale，
                 由 AfterSaleRule 统一判定），前端只做四分支渲染：
                   · eligible=true     → 「申请售后」可点击 → /after-sale/apply/:orderId
                                          （若 csApproved=true 文案改成「继续申请」：
                                           客服已放行，用户此刻要做的是把申请补完）
                   · entryState=CS     → 「联系客服」**可点击** → /service-ticket/apply/:orderId
                   · hasAfterSale=true → 「查看售后」       → /after-sale/:id（进度/撤销）
                   · 其余（未收货/已取消/已彻底超期）→ 置灰按钮，tooltip 显示后端给的 hint

                 【2026-09-28 · 为什么 CS 必须排在置灰分支之前】
                 商品退货期限改为商家按商品设置后，超期买家不再是「彻底没救」，
                 而是要先走客服裁定。CS 分支若不插在 v-else 之前，
                 超期订单永远命中最后一个 else —— 用户只能看到一个点不动的置灰按钮，
                 客服通道等于不存在。

                 兜底：afterSale 字段缺失（接口异常/旧后端）时仍显示可点击的
                 「申请售后」——宁可让后端在一次提交里明确拒绝，也不要因为
                 一个字段缺失就把入口静默吞掉（这正是本次缺陷的表现形式）。
                 ============================================================ -->
            <template v-if="order.orderStatus === 3">
              <el-button
                v-if="afterSaleState(order) === 'apply'"
                type="primary"
                plain
                size="small"
                @click="goApplyAfterSale(order)"
              >
                {{ order.afterSale?.csApproved === true ? t('service_ticket.continue_apply') : t('after_sale.entry') }}
              </el-button>
              <el-button
                v-else-if="afterSaleState(order) === 'view'"
                type="primary"
                link
                size="small"
                @click="goAfterSaleDetail(order)"
              >
                {{ t('after_sale.view') }}
              </el-button>
              <!-- 超期但可联系客服：这是**可点击**入口，不能用置灰按钮糊弄 -->
              <el-button
                v-else-if="afterSaleState(order) === 'cs'"
                type="warning"
                plain
                size="small"
                @click="goContactService(order)"
              >
                {{ t('service_ticket.contact') }}
              </el-button>
              <el-tooltip v-else :content="order.afterSale?.hint || ''" placement="top">
                <el-button type="info" link size="small" disabled>
                  {{ order.afterSale?.code === 'EXPIRED' ? t('after_sale.expired') : t('after_sale.unavailable') }}
                </el-button>
              </el-tooltip>
            </template>
          </div>
        </div>
      </div>
      
      <el-pagination
        v-if="!loading && filteredOrders.length > 0"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="handlePageChange"
        layout="prev, pager, next, jumper"
        class="pagination"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Refresh, ShoppingBag, Clock } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { getImageUrl } from '@/utils/image'
import { ORDER_STATUS_MAP } from '@/utils/orderStatus'
import { getOrderList, updateOrderStatus } from '@/api/order'
import { useNowTicker, remainingMs, formatRemaining } from '@/utils/payCountdown'
import { formatDateTime } from '@/utils/datetime'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const activeTab = ref('all')
const orders = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const payingOrderId = ref(null)
const receivingOrderId = ref(null)

// 每秒心跳：驱动「剩余 mm:ss 自动取消」倒计时。整页共用一个定时器，
// 卸载时由 useNowTicker 内部清理（它注册了 onBeforeUnmount）。
const now = useNowTicker()

// 被 route.query.highlight 命中的订单（用于短暂高亮样式）
const highlightedOrderId = ref(null)
// 高亮与滚动清理用的定时器：组件卸载/重复触发时统一收口，避免残留
let highlightTimer = null

// 状态 → i18n key / 颜色 统一由 @/utils/orderStatus 提供（见该文件说明）
const currentTabName = computed(() => {
  if (activeTab.value === 'all') return ''
  return t(ORDER_STATUS_MAP[activeTab.value]?.text || '') || ''
})

const getStatusText = (status) => {
  return t(ORDER_STATUS_MAP[status]?.text || '') || t('order.unknown')
}

const filteredOrders = computed(() => {
  if (activeTab.value === 'all') {
    return orders.value
  }
  return orders.value.filter(order => order.orderStatus === parseInt(activeTab.value))
})

const getStatusType = (status) => {
  return ORDER_STATUS_MAP[status]?.type || 'info'
}

// ==================== 付款时限 / 取消原因提示（2026-10-09） ====================
//
// 背景：用户提交订单后订单停在「待付款」，此前页面上只有两个按钮（付款/取消），
// 没有任何地方说明「这个单子有时间限制、超时会被系统收走」。等订单被后台任务
// 自动取消后，用户看到状态变成「已取消」——第一反应是「我没点过取消，谁动的？」。
// 这里补上前后两段信息：付款中的剩余时间、取消后的具体原因。

// 「等待付款」= 待付款 且 未付款。只认后端 payStatus，不用 orderStatus 反推。
const isAwaitingPay = (order) =>
  Number(order.orderStatus) === 0 && Number(order.payStatus) !== 1

// 已取消订单的取消原因：只认后端 cancelReason 枚举。
// 缺字段（旧后端/手动取消的历史数据）时返回空串 —— 宁可不说，也不猜一个原因。
const cancelReasonText = (order) => {
  if (Number(order.orderStatus) !== 4 || !order.cancelReason) return ''
  const map = {
    AUTO_TIMEOUT: 'order.cancel_reason_auto_timeout',
    MERCHANT: 'order.cancel_reason_merchant',
    USER: 'order.cancel_reason_user'
  }
  const key = map[String(order.cancelReason)]
  return key ? t(key) : ''
}

// ==================== 未付款预警与倒计时（2026-10-10） ====================

// 待付款订单的剩余毫秒数；payDeadline 缺失时返回 null（不渲染倒计时）
const orderRemaining = (order) => remainingMs(order?.payDeadline, now.value)

// 倒计时是否已经走完。注意与 isAwaitingPay 配合使用：
// 只有「仍处于待付款」的订单才谈得上超时，已取消/已发货的单子不算。
const isDeadlinePassed = (order) => isAwaitingPay(order) && orderRemaining(order) !== null
  && orderRemaining(order) <= 0

/**
 * 卡片内提示条的文案。
 *
 * 归零后刻意换成「正在自动取消」而不是把 00:00 留在屏幕上：后台任务是按周期
 * 扫描的（默认 60 秒一轮），归零到真正取消之间有窗口期，继续显示 00:00
 * 会让用户以为页面卡住或者系统没生效。
 */
const payBannerText = (order) => {
  const ms = orderRemaining(order)
  if (ms === null) return t('order.unpaid_banner_no_deadline')
  if (ms > 0) return t('order.unpaid_banner', { remaining: formatRemaining(ms) })
  return t('order.unpaid_banner_expired')
}

// 当前页签下所有「待付款且未付款」的订单
const awaitingPayOrders = computed(() => orders.value.filter((o) => isAwaitingPay(o)))

// 顶部预警条的副文案：最早一笔的剩余时间（没有 deadline 时退化为通用文案）
const alertDesc = computed(() => {
  const deadlines = awaitingPayOrders.value
    .map((o) => orderRemaining(o))
    .filter((ms) => ms !== null)
  if (deadlines.length === 0) return t('order.unpaid_alert_desc_no_deadline')
  const soonest = Math.min(...deadlines)
  if (soonest <= 0) return t('order.unpaid_alert_desc_expired')
  return t('order.unpaid_alert_desc', { remaining: formatRemaining(soonest) })
})

// 「去付款」目标：点击后要滚动定位到的订单 id（见 loadOrders 尾部消费）
const pendingJumpOrderId = ref(null)

// ==================== 未付款期间的低频轮询 ====================
//
// 【为什么只在这一条件下轮询】用户很可能在另一个标签页、另一台设备上完成付款，
// 本页若不刷新就会一直显示「未付款」并继续倒计时，等于持续给出错误的紧迫感
// （甚至会让人以为付款没成功而重复付款）。所以只在「确实还有待付款订单」时
// 以 30 秒为周期重拉；一旦付清或订单被取消，条件不成立、轮询自动停止，
// 不给正常浏览的用户白打请求。
let unpaidPollTimer = null
const hasAwaitingPay = computed(() => awaitingPayOrders.value.length > 0)
watch(hasAwaitingPay, (yes) => {
  if (yes && !unpaidPollTimer) {
    unpaidPollTimer = setInterval(() => loadOrders(currentPage.value), 30000)
  } else if (!yes && unpaidPollTimer) {
    clearInterval(unpaidPollTimer)
    unpaidPollTimer = null
  }
})

/**
 * 点击预警条上的「去付款」。
 *
 * 【为什么不能直接 highlightOrder】目标订单可能不在当前页签的视图里：
 * 列表在非「全部」页签下是服务端按状态过滤的，用户停在「待收货」时
 * DOM 里根本没有待付款卡片，直接查 DOM 一定为空。
 * 因此先把 id 记下来，切到「待付款」页签并等列表刷新完成后由 loadOrders 消费。
 */
const goPaySoonest = () => {
  const target = awaitingPayOrders.value[0]
  if (!target) return
  if (activeTab.value === '0') {
    highlightOrder(target.id)
  } else {
    pendingJumpOrderId.value = target.id
    activeTab.value = '0'
  }
}

// ==================== 倒计时归零后的补偿刷新 ====================
//
// 【为什么需要】后台任务每分钟才扫一次超时单，所以倒计时归零与订单真正变成
// 「已取消」之间有最多约一分钟的窗口。页面若什么都不做，用户会盯着一个
// 「还剩 00:00」的单子发呆、又看不到取消原因 —— 那正是「系统取消了但没人告诉我」
// 的体验。这里做两层兜底：归零当刻**立刻**拉一次（多数情况下后端可能刚好扫到），
// 之后在「仍存在已归零倒计时」期间以 20 秒为周期重拉；
// 一旦后端把订单改成已取消（不再是待付款单），条件自动失效、轮询停止。
const hasExpiredCountdown = computed(() =>
  orders.value.some((order) => {
    if (!isAwaitingPay(order)) return false
    const ms = remainingMs(order.payDeadline, now.value)
    return ms !== null && ms <= 0
  })
)

let expiryTimer = null
const stopExpiryPolling = () => {
  if (expiryTimer) {
    clearInterval(expiryTimer)
    expiryTimer = null
  }
}

watch(hasExpiredCountdown, (expired) => {
  if (expired && !expiryTimer) {
    // 归零当刻先立即拉一次，再进入周期兜底
    loadOrders(currentPage.value)
    expiryTimer = setInterval(() => loadOrders(currentPage.value), 20000)
  } else if (!expired) {
    stopExpiryPolling()
  }
})

const getTotalQuantity = (order) => {
  if (!order.items) return 0
  return order.items.reduce((sum, item) => sum + item.quantity, 0)
}

// tab切换时重置页码为1，再调用loadOrders加载数据
// Element Plus的@tab-change会传入tab的name值，不能直接绑定loadOrders
const handleTabChange = () => {
  currentPage.value = 1
  loadOrders(1)
}

const loadOrders = async (page = 1) => {
  loading.value = true
  try {
    let status = null
    if (activeTab.value !== 'all') {
      status = parseInt(activeTab.value)
    }
    const res = await getOrderList({ page, size: pageSize.value, status })
    if (res.code === 200 && res.data) {
      orders.value = res.data.records || []
      total.value = res.data.total || 0
    }
    currentPage.value = page
  } catch (error) {
    console.error('加载订单失败', error)
  } finally {
    loading.value = false
  }

  // 「去付款」跳转的收尾：必须等列表数据与 DOM 都就绪后再滚动定位，
  // 否则会查到一个还没渲染出目标卡片的空列表（高亮永远不生效）。
  if (pendingJumpOrderId.value) {
    const id = pendingJumpOrderId.value
    pendingJumpOrderId.value = null
    await highlightOrder(id)
  }
}

const refreshOrders = () => {
  loadOrders(1)
}

const handlePageChange = (page) => {
  loadOrders(page)
}

const payOrder = async (order) => {
  // 【测试报告#5】付款此前是「一键即成功」：点一下直接扣，¥299,950 也一样，
  // 而同页的确认收货、取消订单、删除商品全都有二次确认。付款不可逆，
  // 先弹确认并把实付金额摆出来，让人有机会看一眼金额再点。
  try {
    await ElMessageBox.confirm(
      t('order.pay_confirm', { amount: order.payAmount }),
      t('common.warning'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
  } catch {
    // 用户点了「取消」——主动放弃，不是失败，不弹错误提示
    return
  }

  payingOrderId.value = order.id
  try {
    await updateOrderStatus(order.id, 1)
    ElMessage.success(t('order.pay_success'))
    // 【必须回拉列表】本地只改 orderStatus 是不够的：付款后卡片要摘掉未付款提示条、
    // 顶部预警条的计数要减一、payStatus/payTime 也要更新。这些都是服务端权威字段，
    // 自己拼一份本地状态迟早与后端不一致（同类教训见确认收货后必须 loadOrders）。
    await loadOrders(currentPage.value)
  } catch (error) {
    ElMessage.error(t('order.pay_failed'))
  } finally {
    payingOrderId.value = null
  }
}

const cancelOrder = async (order) => {
  try {
    await ElMessageBox.confirm(t('order.cancel_confirm'), t('common.warning'), { type: 'warning' })
    await updateOrderStatus(order.id, 4)
    ElMessage.success(t('order.cancel_success'))
    // 同样回拉：取消后顶部未付款预警的计数要减一，卡片也要从「未付款」
    // 切成「已取消 · 你已主动取消该订单」，这些都以服务端返回为准。
    await loadOrders(currentPage.value)
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(t('order.cancel_failed'))
    }
  }
}

const confirmReceive = async (order) => {
  receivingOrderId.value = order.id
  try {
    await ElMessageBox.confirm(t('order.receive_confirm'), t('common.warning'), { type: 'info' })
    await updateOrderStatus(order.id, 3)
    ElMessage.success(t('order.receive_success'))
    // 【缺陷修复 · 关键一步】确认收货后必须重新拉列表。
    // 售后入口是否出现，取决于后端按 completeTime 重新判定的 order.afterSale；
    // 原先这里只写 `order.orderStatus = 3` 改本地状态 —— 即使补上按钮，
    // afterSale 仍是收货前那份（未收货 → 不可申请），入口照样不会出现。
    await loadOrders(currentPage.value)
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(t('order.operate_failed'))
    }
  } finally {
    receivingOrderId.value = null
  }
}

// ==================== 售后入口（确认收货后） ====================
//
// 入口状态只消费后端裁定结果（order.afterSale，由 common/AfterSaleRule 生成），
// 前端**不**自行计算 15 天/7 天时效，避免与提交接口的口径不一致
// （那会导致「按钮能点但提交被拒」这种最难解释的交互）。
const afterSaleState = (order) => {
  const info = order.afterSale
  // 兜底：接口未回填时保留可点击入口，由后端在提交时给出明确拒绝原因
  if (!info) return 'apply'
  // 【R15】后端已裁定入口形态（entryState），前端不再自行推导：
  // 否则「未付款」会被当成 expired，显示成「售后已过期」——文案与事实不符。
  // 取值：apply | view | cs | disabled —— cs 是 2026-09-28 新增的
  // 「超期但可联系客服」形态，这里显式收敛成小写 'cs'，与模板分支一一对应。
  if (info.entryState) {
    const state = String(info.entryState).toLowerCase()
    if (state === 'cs') return 'cs'
    return state
  }
  // 旧后端没有 entryState 时的兜底推导。csRequired 必须排在 eligible/hasAfterSale
  // 判断之前：超期且存在历史售后单时，用户该做的是联系客服而不是翻旧单。
  if (info.eligible === true) return 'apply'
  if (info.csRequired === true) return 'cs'
  if (info.hasAfterSale === true) return 'view'
  return 'disabled'
}

const goApplyAfterSale = (order) => {
  router.push(`/after-sale/apply/${order.id}`)
}

/**
 * 超期订单的客服通道入口。
 *
 * 【为什么单独一个方法】跳转目标与「申请售后」不同：售后申请页会立刻按
 * 时效规则校验并拒绝超期单，用户会觉得「点了没用」；客服工单页则是先提交
 * 申诉、由客服依据商家标准裁定放行。两条链路必须分开。
 */
const goContactService = (order) => {
  router.push(`/service-ticket/apply/${order.id}`)
}

const goAfterSaleDetail = (order) => {
  const id = order.afterSale?.latestAfterSaleId
  if (id) {
    router.push(`/after-sale/${id}`)
  } else {
    // 理论上 hasAfterSale=true 一定带 latestAfterSaleId；缺失时退到汇总列表
    router.push('/after-sale')
  }
}

const reorder = (order) => {
  ElMessage.success(t('order.reorder_success'))
  router.push('/cart')
}

/**
 * 定位并短暂高亮某个订单卡片。
 *
 * 【为什么需要】商品详情页「直接购买」下单成功后跳 /orders?highlight=<id>，
 * 新订单可能排在第二页或列表靠下的位置 —— 用户回来只看到一屏旧订单，
 * 很容易以为下单失败又买一遍。
 *
 * 【为什么找不到就静默忽略】highlight 只是体验增强，不是业务逻辑：
 * 订单可能已被删除、或不在当前 tab/页码下。这里既不发额外请求、也不改分页，
 * 更不弹错 —— 一旦报错就会把「下单成功」这个主结果盖掉。
 */
const highlightOrder = async (orderId) => {
  // 等 el-card / 列表把 DOM 渲染出来再查，否则 getElementById 一定为空
  await nextTick()
  const el = document.getElementById(`order-${orderId}`)
  if (!el) return

  highlightedOrderId.value = orderId
  // scrollIntoView 的 block:'center' 比默认的 'start' 更稳：
  // 订单卡片较高时贴顶滚动会让卡片上半部分被页面头部遮住。
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })

  if (highlightTimer) clearTimeout(highlightTimer)
  // 高亮只是「指一下」，3 秒后自动褪去，避免用户以为这是订单的常驻状态标记
  highlightTimer = setTimeout(() => {
    highlightedOrderId.value = null
    highlightTimer = null
  }, 3000)
}

/**
 * 把 `route.query.status` 同步到 activeTab（深链支持）。
 *
 * 【2026-10-10 修复】首页那 5 个状态格原本一律跳 `/orders`，点哪个都落在
 * 「全部订单」页签，格子上写着「已取消 2」结果一进去看到的却是全部，
 * 信息对不上。现在状态格带 `?status=N` 过来，这里负责把它落成页签。
 *
 * 只接受 0~4 五个合法值，其余（含缺失、乱填）一律回落 `all` ——
 * 否则 `parseInt` 出来的野值会被原样发给后端。
 */
const applyStatusQuery = () => {
  const raw = String(route.query.status ?? '').trim()
  activeTab.value = ['0', '1', '2', '3', '4'].includes(raw) ? raw : 'all'
}

onMounted(async () => {
  // 必须先落页签再拉数据，否则会先按 all 拉一次、再按 status 拉一次（白打一发请求）
  applyStatusQuery()
  await loadOrders()
  // 必须在列表加载完成之后再定位：loadOrders 是异步的，
  // 先查 DOM 会查到一个空列表（P0 级别的「高亮永远不生效」）。
  const highlightId = route.query.highlight
  if (highlightId) {
    await highlightOrder(highlightId)
  }
})

// 已在本页时再点一次带不同 status 的链接（组件不会重新挂载），靠 watch 接住
watch(() => route.query.status, async () => {
  const before = activeTab.value
  applyStatusQuery()
  if (before !== activeTab.value) {
    handleTabChange()
  }
})

// 离开页面时清掉高亮定时器：它在回调里写 ref，组件卸载后再触发
// 虽然 Vue 不会报错，但会白留一个引用到全局列表里。
onBeforeUnmount(() => {
  if (highlightTimer) {
    clearTimeout(highlightTimer)
    highlightTimer = null
  }
  // 倒计时归零期间的补偿轮询同样要停掉（见 hasExpiredCountdown 的说明）
  stopExpiryPolling()
  // 未付款期间的 30 秒轮询（见 hasAwaitingPay 的说明）
  if (unpaidPollTimer) {
    clearInterval(unpaidPollTimer)
    unpaidPollTimer = null
  }
})
</script>

<style scoped>
.order-list-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.loading-state,
.empty-state {
  text-align: center;
  padding: 60px 0;
  color: var(--text-muted);
}

.empty-icon {
  margin-bottom: 16px;
  color: var(--text-muted);
}

.order-list {
  margin-top: 20px;
}

.order-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  margin-bottom: 16px;
  overflow: hidden;
  transition: box-shadow 0.3s ease, border-color 0.3s ease;
}

/* 「直接购买」下单后跳转过来的定位高亮：只做视觉指引，3 秒后由脚本摘掉类名。
   用 box-shadow + border-color 而不是 background —— 卡片内部各区块
   都有自己的背景色，改背景等于把整块配色打乱。 */
.order-card-highlight {
  border-color: var(--accent-color, #409eff);
  box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.25);
}

.order-header {
  display: flex;
  justify-content: space-between;
  padding: 16px 20px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
}

.order-no {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

/* 已存在售后单时的标识（与「查看售后」按钮呼应） */
.sale-tag {
  margin-left: 8px;
}

.order-time {
  font-size: 12px;
  color: var(--text-muted);
}

.order-items {
  padding: 16px 20px;
}

/* ===== 未付款提示（2026-10-10） ===== */

/* 顶部队列级预警条：与表格/卡片之间留白，避免看起来像列表的一部分 */
.pay-alert {
  margin-bottom: 16px;
}

.pay-alert-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.pay-alert-desc {
  font-size: 13px;
}

/* 卡片内的整行倒计时条：整幅横跨卡片，用警示底色把「这单有时限」从
   商品清单与金额里拎出来（原先只是状态标签旁的一行 12px 灰字，实测无人注意） */
.unpaid-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  font-size: 13px;
  color: #e6a23c;
  background: rgba(230, 162, 60, 0.12);
  border-top: 1px solid rgba(230, 162, 60, 0.35);
  border-bottom: 1px solid rgba(230, 162, 60, 0.35);
}

/* 倒计时归零、等待后台任务真正取消：从「还剩多久」升级为重色告警，
   与文案「正在自动取消」保持一致，避免用户以为页面卡在 00:00 */
.unpaid-banner-expired {
  color: #f56c6c;
  background: rgba(245, 108, 108, 0.14);
  border-color: rgba(245, 108, 108, 0.4);
}

.unpaid-banner-icon {
  font-size: 15px;
}

.unpaid-banner-text {
  flex: 1;
  font-weight: 500;
}

.unpaid-banner-btn {
  flex-shrink: 0;
}

.order-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px dashed var(--border-color);
}

.order-item:last-child {
  border-bottom: none;
}

.item-image {
  width: 60px;
  height: 60px;
  object-fit: cover;
  background: var(--bg-secondary);
  border-radius: 4px;
}

.item-info {
  flex: 1;
}

.item-name {
  font-size: 14px;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.item-spec {
  font-size: 12px;
  color: var(--text-muted);
}

.item-price {
  font-size: 14px;
  font-weight: 500;
  color: #f56c6c;
}

.item-quantity {
  font-size: 12px;
  color: var(--text-muted);
}

.order-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: var(--bg-secondary);
}

.order-status {
  flex-shrink: 0;
}

/* 已取消订单的取消原因：跟在状态标签下方一行。
   （待付款的倒计时已上移为卡片内的 .unpaid-banner，这里只剩取消原因） */
.pay-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.4;
}

.order-total {
  display: flex;
  align-items: center;
  gap: 20px;
  font-size: 14px;
  color: var(--text-secondary);
}

.total-amount {
  font-size: 16px;
  font-weight: 500;
}

.total-amount .amount {
  font-size: 18px;
  font-weight: bold;
  color: #f56c6c;
}

.order-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid var(--border-color);
}

.pagination {
  margin-top: 20px;
  text-align: right;
}
</style>
