<template>
  <div class="order-manage">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('order.manage') }}</span>
          <el-button type="primary" size="small" :loading="loading" @click="loadOrders">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>

      <!-- ============================================================
           【未付款预警条 · 2026-10-09】
           起因：用户提交订单后尚未付款，商家端在这一列上只显示状态标签
           （待付款/待发货），操作列却直接给出「确认发货」「关闭」——
           商家看不出钱到底到没到账，只能靠猜。
           这条预警把「有几单没给钱」提到页面最上方：只要 > 0 就出现，
           与表格里的付款状态列形成两级提示（全局概览 + 单笔明细）。
           ============================================================ -->
      <el-alert
        v-if="!loading && unpaidCount > 0"
        class="unpaid-alert"
        type="warning"
        show-icon
        :closable="false"
        :title="t('order_manage.unpaid_alert', { count: unpaidCount })"
      />

      <el-table :data="orders" stripe v-loading="loading">
        <el-table-column prop="orderNo" :label="t('order.order_no')" width="180" />
        <el-table-column :label="t('order_manage.user')" width="110">
          <template #default="{ row }">
            {{ row.username || '-' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('order_manage.product')" min-width="180">
          <template #default="{ row }">
            <template v-if="row.items && row.items.length > 0">
              <div v-for="item in row.items" :key="item.id" class="order-item">
                {{ item.productName }} x{{ item.quantity }}
              </div>
            </template>
            <span v-else class="no-data">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="payAmount" :label="t('order_manage.amount')" width="110">
          <template #default="{ row }">
            <span class="amount">¥{{ row.payAmount }}</span>
          </template>
        </el-table-column>
        <!-- ============================================================
             付款状态列（2026-10-09 新增）
             数据来源是 order.payStatus（0 未付款 / 1 已付款），与金额同源于服务端，
             不由前端按订单状态倒推 —— 「待发货」这个状态在本项目里既能由用户付款
             产生、也能由商家直接发货跳过，只看 orderStatus 会误判。
             未付款单额外显示「剩余 mm:ss 自动取消」：截止时间取自后端回填的
             order.payDeadline（= 创建时间 + 配置的超时分钟数），前端不自己算时长。
             ============================================================ -->
        <el-table-column :label="t('order_manage.pay_status')" width="150">
          <template #default="{ row }">
            <div class="pay-cell">
              <el-tag
                :type="isPaid(row) ? 'success' : 'danger'"
                :effect="isPaid(row) ? 'light' : 'dark'"
                size="small"
              >
                {{ isPaid(row) ? t('order_manage.paid') : t('order_manage.unpaid') }}
              </el-tag>
              <div v-if="paySubText(row)" class="pay-sub" :class="{ 'pay-countdown': isAwaitingPay(row) }">
                {{ paySubText(row) }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="orderStatus" :label="t('order.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.orderStatus)">{{ getStatusText(row.orderStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('order.shipping_info')" min-width="150">
          <template #default="{ row }">
            <div class="shipping-info" v-if="row.shippingName">
              <div>{{ row.shippingName }}</div>
              <div class="shipping-phone">{{ row.shippingPhone }}</div>
            </div>
            <span v-else class="no-data">-</span>
          </template>
        </el-table-column>
        <!-- 【订单管理按钮修复·2026-09-23】原实现唯一的「发货」按钮挂在
             v-if="row.orderStatus === 1"（严格相等）上，导致：
             ① 用户实测中的「待付款(0)」订单整列空白 —— 表现为"操作按钮不显示"；
             ② 严格 === 对状态的序列化类型（数字/字符串）零容错，稍有偏差按钮即消失；
             ③ 其余状态没有任何操作入口与占位提示。
             现改为按状态归一渲染（Number() 归一化）：未发货(0/1)显示「发货/关闭」，
             其余状态给出占位符，操作列不再留白。
             【2026-10-09 补充】未付款(0) 的订单在「发货」按钮上加感叹号角标与
             tooltip，点击后还会再弹一次风险确认 —— 按钮保留是因为该状态本来就
             允许商家抢先发货，但绝不能再让它看起来和黄付款单一样理所当然。 -->
        <el-table-column :label="t('order.actions')" width="180">
          <template #default="{ row }">
            <template v-if="isUnshipped(row)">
              <el-tooltip
                v-if="!isPaid(row)"
                :content="t('order_manage.unpaid')"
                placement="top"
              >
                <el-button type="primary" size="small" @click="openShipDialog(row)">
                  <el-icon class="btn-warn-icon"><WarningFilled /></el-icon>
                  {{ t('order_manage.ship') }}
                </el-button>
              </el-tooltip>
              <el-button v-else type="primary" size="small" @click="openShipDialog(row)">{{ t('order_manage.ship') }}</el-button>
              <el-button type="danger" size="small" @click="closeOrder(row)">{{ t('order_manage.close') }}</el-button>
            </template>
            <span v-else class="no-data">{{ statusFinishedHint(row) }}</span>
          </template>
        </el-table-column>
      </el-table>
      
      <el-empty v-if="!loading && orders.length === 0" :description="t('order_manage.no_data')" />
    </el-card>

    <el-dialog :title="t('order_manage.ship_title')" v-model="shipDialogVisible" width="440px">
      <el-form :model="shipForm" label-width="90px">
        <el-form-item :label="t('order_manage.logistics_company')" required>
          <el-input v-model="shipForm.logisticsCompany" :placeholder="t('order_manage.logistics_company_placeholder')" />
        </el-form-item>
        <el-form-item :label="t('order_manage.tracking_no')" required>
          <el-input v-model="shipForm.trackingNo" :placeholder="t('order_manage.tracking_no_placeholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="shipping" @click="submitShip">{{ t('order_manage.confirm_ship') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { getOrderList, updateOrderStatus, shipOrder } from '@/api/order'
import { ORDER_STATUS_MAP } from '@/utils/orderStatus'
import { useNowTicker, remainingMs, formatRemaining } from '@/utils/payCountdown'
import { formatDateTime } from '@/utils/datetime'

// 【R20】全页 i18n：表头、弹窗、按钮与错误提示统一走语言包。
// 订单状态文案复用 order.status_* 既有键；通用词（取消/提示）复用 common.*。
const { t } = useI18n()

const orders = ref([])
const loading = ref(false)

// 列表自动刷新间隔：付款状态是分钟级信息，30 秒足够，也不必为它引入推送通道。
const REFRESH_INTERVAL_MS = 30000
let refreshTimer = null

// 每秒心跳，驱动未付款订单的「剩余 mm:ss 自动取消」倒计时刷新。
// 用共享心跳而不是每行一个定时器：一页最多 100 单，各自计时会让数字错落跳动。
const now = useNowTicker()

// 发货对话框（走 /order/ship：录入物流单号，同时是库存扣减触发点）
const shipDialogVisible = ref(false)
const shipping = ref(false)
const currentShipOrder = ref(null)
const shipForm = ref({ logisticsCompany: '', trackingNo: '' })

// 状态归一化：无论后端序列化成数字还是字符串，都能正确比较
const statusCode = (row) => Number(row.orderStatus)

// 未发货（待付款 0 / 待发货 1）的订单允许商家发货、关闭
const isUnshipped = (row) => {
  const code = statusCode(row)
  return code === 0 || code === 1
}

// ==================== 付款状态（2026-10-09 新增） ====================
//
// 唯一判据是后端下发的 payStatus（0 未付款 / 1 已付款），**不能**用
// orderStatus 反推：本项目的「待发货(1)」既可能来自用户付款，也可能来自
// 商家在未付款时直接点发货（该状态允许 0/1 → 2）。按 orderStatus 判断，
// 会出现「商家自己发的货被标成已付款」这种直接误导。
const isPaid = (row) => Number(row.payStatus) === 1

// 「正在等待付款」= 待付款状态且确实未付款。只有这种订单才需要倒计时：
// 已取消/已发货的订单即便 payStatus 仍是 0，也不该再显示「x 分钟后自动取消」
// （超时回收只针对待付款单，对这些订单倒计时永远不兑现，是明确的假信息）。
const isAwaitingPay = (row) => statusCode(row) === 0 && !isPaid(row)

// 已付款 → 显示付款时间；等待付款 → 显示自动取消倒计时；否则显示取消原因（若有）
const paySubText = (row) => {
  if (isPaid(row)) {
    const at = formatTime(row.payTime)
    return at ? `${t('order_manage.pay_time')}：${at}` : ''
  }
  if (isAwaitingPay(row)) {
    // 截止时间来自后端 order.payDeadline；缺失（旧后端/字段未回填）时退化为
    // 「即将自动取消」，绝不自己拿 createTime 加 15 分钟去猜。
    const ms = remainingMs(row.payDeadline, now.value)
    if (ms !== null && ms > 0) {
      return t('order_manage.auto_cancel_in', { remaining: formatRemaining(ms) })
    }
    if (ms !== null) {
      return t('order_manage.auto_cancel_imminent')
    }
    return ''
  }
  return cancelReasonText(row)
}

// 取消原因：只有真的被取消(4)时才展示，且只认后端 cancelReason 枚举
const cancelReasonText = (row) => {
  if (statusCode(row) !== 4 || !row.cancelReason) return ''
  const map = {
    AUTO_TIMEOUT: 'order_manage.cancel_reason_timeout',
    MERCHANT: 'order_manage.cancel_reason_merchant',
    USER: 'order_manage.cancel_reason_user'
  }
  const key = map[String(row.cancelReason)]
  return key ? t(key) : ''
}

// 未付款的待处理订单数 → 顶部预警条。只要 > 0 就提示，覆盖「有单要盯着」这件事。
const unpaidCount = computed(
  () => orders.value.filter((o) => isAwaitingPay(o)).length
)

// 【2026-10-10】原先这里有一份本地的 MM-DD HH:mm 格式化，与全站其它页面
// 直接渲染 ISO 原串（`2026-10-10T16:52:25`）并存，看起来像两个系统拼起来的。
// 现统一走 @/utils/datetime，全站时间一律 `YYYY-MM-DD HH:mm`。
const formatTime = (value) => formatDateTime(value, { empty: '' })

const statusFinishedHint = (row) => {
  const code = statusCode(row)
  return code === 2 ? t('order_manage.shipped') : (code === 4 ? t('order_manage.closed') : '-')
}

// 状态 → i18n key / 颜色 统一由 @/utils/orderStatus 提供（见该文件说明）。
// 状态 4「已取消」与订单列表、采购管理统一为 info。
const getStatusText = (status) => {
  const key = ORDER_STATUS_MAP[Number(status)]?.text
  return key ? t(key) : t('order.unknown')
}

const getStatusType = (status) => {
  return ORDER_STATUS_MAP[Number(status)]?.type || 'info'
}

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await getOrderList({ page: 1, size: 100 })
    if (res.code === 200 && res.data) {
      orders.value = res.data.records || []
    }
  } catch (error) {
    console.error(t('order_manage.load_failed'), error)
  } finally {
    loading.value = false
  }
}

const openShipDialog = (row) => {
  currentShipOrder.value = row
  shipForm.value = { logisticsCompany: '', trackingNo: '' }
  shipDialogVisible.value = true
}

const submitShip = async () => {
  const form = shipForm.value
  if (!form.logisticsCompany.trim()) {
    ElMessage.warning(t('order_manage.logistics_company_required'))
    return
  }
  if (!form.trackingNo.trim()) {
    ElMessage.warning(t('order_manage.tracking_no_required'))
    return
  }

  // 【未付款二次确认 · 2026-10-09】未付款订单仍允许发货（该状态本就放行 0/1 → 2），
  // 但必须在真正发货**之前**让商家明确知道「这单钱还没到」，并给出剩余时限。
  // 放在校验之后、调接口之前：这是不可逆动作的最后一道确认点。
  if (currentShipOrder.value && !isPaid(currentShipOrder.value)) {
    const ms = remainingMs(currentShipOrder.value.payDeadline, now.value)
    const content = (ms !== null && ms > 0)
      ? t('order_manage.ship_unpaid_confirm', {
          orderNo: currentShipOrder.value.orderNo,
          remaining: formatRemaining(ms)
        })
      : t('order_manage.ship_unpaid_confirm_no_deadline', {
          orderNo: currentShipOrder.value.orderNo
        })
    try {
      await ElMessageBox.confirm(content, t('common.warning'), { type: 'warning' })
    } catch (e) {
      return // 商家放弃发货
    }
  }

  shipping.value = true
  try {
    await shipOrder(currentShipOrder.value.id, {
      logisticsCompany: form.logisticsCompany.trim(),
      trackingNo: form.trackingNo.trim()
    })
    ElMessage.success(t('order_manage.ship_success'))
    shipDialogVisible.value = false
    await loadOrders()
  } catch (error) {
    // request 拦截器已统一提示具体原因（如库存不足），这里不重复弹窗
    console.error(t('order_manage.ship_failed'), error)
  } finally {
    shipping.value = false
  }
}

const closeOrder = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('order_manage.close_confirm', { orderNo: row.orderNo }),
      t('common.warning')
    )
  } catch (e) {
    return // 用户取消
  }
  try {
    await updateOrderStatus(row.id, 4)
    ElMessage.success(t('order_manage.close_success'))
    await loadOrders()
  } catch (error) {
    console.error(t('order_manage.close_failed'), error)
  }
}

onMounted(() => {
  loadOrders()
  // 【付款状态「实时」可见 · 2026-10-09】
  // 本页原来只在进入时拉一次列表：用户在另一端付款后，商家不刷新页面就一直是
  // 「未付款」，会误判成用户赖账。这里加一个低频轮询把付款状态带到眼前。
  // 30 秒是权衡值：付款状态属于「分钟级」信息，不需要秒级推送；
  // 用轮询而非 WebSocket 是因为本项目没有消息中间件，也不值得为这一点引入。
  refreshTimer = setInterval(loadOrders, REFRESH_INTERVAL_MS)
})

onBeforeUnmount(() => {
  // 定时器必须清理：回调里会写 orders ref 并打网络请求，
  // 页面离开后继续跑就是纯粹的浪费与潜在报错。
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})
</script>

<style scoped>
.order-manage {
  padding: 20px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.order-item {
  font-size: 13px;
  padding: 2px 0;
}

.amount {
  color: #f56c6c;
  font-weight: bold;
}

.shipping-info {
  font-size: 13px;
  line-height: 1.6;
}

.shipping-phone {
  color: #909399;
  font-size: 12px;
}

.no-data {
  color: #c0c4cc;
  font-size: 13px;
}

/* 未付款预警条：与表格之间留出间距，避免贴着表头显得像表的一部分 */
.unpaid-alert {
  margin-bottom: 12px;
}

/* 付款状态单元格：标签 + 一行补充信息（付款时间 / 倒计时 / 取消原因） */
.pay-cell {
  line-height: 1.5;
}

.pay-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

/* 倒计时用警示色：这是「再不付款单子就没了」的信息，不能和普通灰字同权重 */
.pay-sub.pay-countdown {
  color: #e6a23c;
  font-weight: 500;
}

/* 未付款订单「发货」按钮上的感叹号：给按钮加第二个视觉信号，
   避免商家在黄色标签之外完全没注意到这笔钱还没到账 */
.btn-warn-icon {
  margin-right: 4px;
}
</style>
