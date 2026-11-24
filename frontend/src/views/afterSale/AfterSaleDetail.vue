<template>
  <div class="after-sale-detail-page">
    <el-card v-loading="loading">
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('after_sale.detail_title') }}</span>
          <el-button size="small" @click="goBack">{{ t('common.back') }}</el-button>
        </div>
      </template>

      <template v-if="detail.id">
        <!-- 状态横幅 -->
        <div class="status-banner">
          <el-tag :type="afterSaleStatusType(detail.status)" size="large">
            {{ t(afterSaleStatusKey(detail.status)) }}
          </el-tag>
          <span class="status-hint">{{ statusHint }}</span>
        </div>

        <!-- 处理进度 -->
        <!-- 【测试报告#6】这三个 description 原先直塞 detail.applyTime 原串，
             渲染出「2026-10-10T20:27:04」这种带 T 的 ISO，与本页上方 descriptions
             里已走 formatDateTime 的「2026-10-10 20:27」并存。统一走 datetime.js 出口；
             未走到的节点保持不显示（empty: ''），不冒出一个孤零零的 "-"。 -->
        <el-steps :active="stepActive" :process-status="stepStatus" finish-status="success" align-center class="steps">
          <el-step :title="t('after_sale.step_applied')" :description="formatDateTime(detail.applyTime, { empty: '' })" />
          <el-step :title="t('after_sale.step_audited')" :description="formatDateTime(detail.auditTime, { empty: '' })" />
          <el-step :title="t('after_sale.step_finished')" :description="formatDateTime(detail.finishTime, { empty: '' })" />
        </el-steps>

        <el-alert
          v-if="Number(detail.status) === 3"
          type="error"
          :closable="false"
          show-icon
          class="tip-alert"
          :title="t('after_sale.rejected_tip')"
          :description="detail.auditRemark || ''"
        />
        <el-alert
          v-else-if="Number(detail.status) === 4"
          type="info"
          :closable="false"
          show-icon
          class="tip-alert"
          :title="t('after_sale.cancelled_tip')"
        />

        <!-- 【R13】金额区边界提示：本项目的售后只做「状态流转 + 库存回补」，
             没有接任何支付网关 —— 「退款完成」是一个状态，不是一笔真实资金往来。
             不写清楚，演示/验收时极易把状态流转误认为真的退了钱。 -->
        <el-alert
          type="info"
          show-icon
          class="tip-alert"
          :title="t('after_sale.demo_notice')"
        />

        <!-- 基本信息 -->
        <el-descriptions :column="2" border class="info-block">
          <el-descriptions-item :label="t('after_sale.after_sale_no')">{{ detail.afterSaleNo }}</el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.order_no')">{{ detail.orderNo || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.type')">{{ t(afterSaleTypeKey(detail.type)) }}</el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.amount')">
            <span class="amount">¥{{ detail.refundAmount }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.reason')">{{ reasonText(detail.reasonCode, detail.reason) }}</el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.apply_time')">{{ formatDateTime(detail.applyTime) }}</el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.audit_time')">{{ formatDateTime(detail.auditTime) }}</el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.finish_time')">{{ formatDateTime(detail.finishTime) }}</el-descriptions-item>
          <el-descriptions-item :label="t('after_sale.description')" :span="2">
            {{ detail.description || '-' }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detail.auditRemark"
            :label="t('after_sale.audit_remark')"
            :span="2"
          >
            {{ detail.auditRemark }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 凭证图片 -->
        <div v-if="detail.images && detail.images.length" class="evidence-block">
          <div class="block-title">{{ t('after_sale.images') }}</div>
          <div class="thumb-list">
            <img v-for="url in detail.images" :key="url" :src="getImageUrl(url)" class="thumb" />
          </div>
        </div>

        <!-- 商品清单（取自订单，需买家本人可见） -->
        <div v-if="order.items && order.items.length" class="evidence-block">
          <div class="block-title">{{ t('after_sale.order_products') }}</div>
          <div v-for="item in order.items" :key="item.id" class="order-item">
            <img :src="getImageUrl(item.productImage)" class="item-image" />
            <div class="item-name">{{ item.productName }}</div>
            <div class="item-qty">x{{ item.quantity }}</div>
            <div class="item-price">¥{{ item.price }}</div>
          </div>
          <div class="total-line">
            <span>{{ t('after_sale.pay_amount') }}</span>
            <span class="amount">¥{{ order.payAmount }}</span>
          </div>
        </div>

        <!-- 【P1-6】本次售后商品明细：仅按商品售后（item_scope=1）展示，整单售后不显示 -->
        <div
          v-if="Number(detail.itemScope) === 1 && detail.saleItems && detail.saleItems.length"
          class="evidence-block"
        >
          <div class="block-title">{{ t('after_sale.sale_products') }}</div>
          <div v-for="si in detail.saleItems" :key="si.id" class="order-item">
            <img v-if="saleItemOf(si).productImage" :src="getImageUrl(saleItemOf(si).productImage)" class="item-image" />
            <div class="item-name">{{ saleItemName(si) }}</div>
            <div class="item-qty">x{{ si.quantity }}</div>
            <div class="item-price">¥{{ si.amount }}</div>
          </div>
        </div>

        <div class="detail-actions">
          <el-button v-if="Number(detail.status) === 0" type="danger" plain @click="cancelApply">
            {{ t('after_sale.cancel_apply') }}
          </el-button>
          <el-button v-if="canReapply" type="primary" @click="reapply">
            {{ t('after_sale.reapply') }}
          </el-button>
          <el-button @click="goBack">{{ t('after_sale.go_orders') }}</el-button>
        </div>
      </template>

      <el-empty v-else-if="!loading" :description="t('after_sale.no_data')" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelAfterSale,
  getAfterSaleDetail,
  getOrderEligibility
} from '@/api/afterSale'
import { getOrderById } from '@/api/order'
import { getImageUrl } from '@/utils/image'
import { afterSaleStatusKey, afterSaleStatusType, afterSaleTypeKey } from '@/utils/afterSale'
import { useAfterSaleReasons } from '@/composables/useAfterSaleReasons'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
// 【2026-10-10】原因按 reasonCode 走本地化字典；detail.reason 是申请时写库的中文快照，仅作兜底
const { text: reasonText } = useAfterSaleReasons()

const loading = ref(false)
const detail = ref({})
const order = ref({})
const eligibility = ref({})
const statusHint = ref('')

const statusNum = computed(() => Number(detail.value.status))

const stepActive = computed(() => {
  if (statusNum.value === 2) return 3
  if (statusNum.value === 1) return 2
  return 1
})

const stepStatus = computed(() => (statusNum.value === 3 ? 'error' : 'process'))

// 【P1-6】after_sale_item 只有 orderItemId/productId/quantity/amount，没有商品名/图。
// 从同一订单的明细里按 orderItemId 关联出展示字段（取不到时兜底为 #productId）。
const saleItemOf = (si) =>
  (order.value.items || []).find((it) => it.id === si.orderItemId) || {}
const saleItemName = (si) => saleItemOf(si).productName || `#${si.productId}`

// 被拒绝 / 已撤销后，只要还在申请时效内就允许重新申请
const canReapply = computed(
  () => (statusNum.value === 3 || statusNum.value === 4) && eligibility.value.eligible === true
)

const loadData = async () => {
  const id = route.params.id
  loading.value = true
  try {
    const res = await getAfterSaleDetail(id)
    detail.value = res.data || {}
    if (detail.value.orderId) {
      // 订单可能因权限/删除取不到，失败不影响主信息展示
      const [orderRes, eligRes] = await Promise.all([
        getOrderById(detail.value.orderId).catch(() => ({ data: {} })),
        getOrderEligibility(detail.value.orderId).catch(() => ({ data: {} }))
      ])
      order.value = orderRes.data || {}
      eligibility.value = eligRes.data || {}
      statusHint.value = eligibility.value.hint || ''
    }
  } catch (e) {
    console.error('加载售后详情失败', e)
  } finally {
    loading.value = false
  }
}

const cancelApply = async () => {
  try {
    await ElMessageBox.confirm(t('after_sale.cancel_confirm'), t('common.warning'), { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await cancelAfterSale(detail.value.id)
    ElMessage.success(t('after_sale.cancel_success'))
    await loadData()
  } catch (e) {
    console.error('撤销售后失败', e)
  }
}

const reapply = () => router.push(`/after-sale/apply/${detail.value.orderId}`)
const goBack = () => router.push('/orders')

onMounted(loadData)
</script>

<style scoped>
.after-sale-detail-page {
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

.status-banner {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}

.status-hint {
  font-size: 13px;
  color: var(--text-muted);
}

.steps {
  margin: 10px 0 24px;
}

.tip-alert {
  margin-bottom: 16px;
}

.info-block {
  margin-bottom: 20px;
}

.amount {
  color: #f56c6c;
  font-weight: bold;
}

.evidence-block {
  margin-bottom: 20px;
}

.block-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 10px;
}

.thumb-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.thumb {
  width: 84px;
  height: 84px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--border-color);
}

.order-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--border-color);
}

.item-image {
  width: 44px;
  height: 44px;
  object-fit: cover;
  border-radius: 4px;
  background: var(--bg-secondary);
}

.item-name {
  flex: 1;
  color: var(--text-primary);
}

.item-qty,
.item-price {
  color: var(--text-muted);
}

.total-line {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 10px;
  padding-top: 10px;
  font-size: 14px;
  color: var(--text-secondary);
}

.detail-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
}
</style>
