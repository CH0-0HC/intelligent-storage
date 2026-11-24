<template>
  <div class="kefu-ticket-detail-page">
    <el-card v-loading="loading">
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('service_ticket.resolve_title') }}</span>
          <div class="header-right">
            <el-tag v-if="ticket.id" :type="statusType" size="small">{{ statusText }}</el-tag>
            <el-button size="small" @click="goBack">{{ t('common.back') }}</el-button>
          </div>
        </div>
      </template>

      <!-- 【测试报告#3】工单不存在（如直接打开 /kefu/ticket/9999）：
           后端返回空 data 时，原先 `ticket.value = ticketRes.data || {}` 得到空对象，
           Number(undefined) = NaN !== 0 让 locked 恒为 true，于是页面既渲染一屏 "-" 占位，
           又弹「该工单已裁定，不可重复处理」并给出裁定选项 —— 误导客服以为工单已处理。
           这里改为明确的「工单不存在」空态 + 返回列表入口。 -->
      <el-empty v-if="notFound" :description="t('service_ticket.not_found')">
        <el-button type="primary" @click="goBack">{{ t('service_ticket.back_list') }}</el-button>
      </el-empty>

      <template v-else>
      <!-- 工单全量信息：客服裁定的全部事实依据摆在同一屏，
           避免「看一半去翻订单详情」导致裁定依据不完整 -->
      <div class="detail-block">
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.ticket_no') }}</span>
          <span class="value">{{ ticket.ticketNo || '-' }}</span>
          <span class="label">{{ t('service_ticket.order_no') }}</span>
          <span class="value">{{ ticket.orderNo || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.buyer') }}</span>
          <span class="value">{{ ticket.buyerName || '-' }}</span>
          <span class="label">{{ t('service_ticket.product') }}</span>
          <span class="value">{{ ticket.productName || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.return_window') }}</span>
          <span class="value">{{ windowText }}</span>
          <span class="label">{{ t('service_ticket.days_overdue') }}</span>
          <span class="value overdue">{{ Number(ticket.daysOverdue) || 0 }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.reason') }}</span>
          <span class="value">{{ reasonText(ticket.reasonCode) }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.description') }}</span>
          <span class="value multi-line">{{ ticket.description || '-' }}</span>
        </div>
        <div v-if="images.length" class="detail-row">
          <span class="label">{{ t('service_ticket.images') }}</span>
          <span class="value">
            <!-- imageList 只算一次，preview-src-list 与实际渲染保持同一顺序（否则预览会错位） -->
            <el-image
              v-for="(url, idx) in images"
              :key="url"
              :src="getImageUrl(url)"
              :preview-src-list="imageList"
              :initial-index="idx"
              :preview-teleported="true"
              fit="cover"
              class="voucher-image"
            />
          </span>
        </div>
        <div class="detail-row">
          <!-- 工单本身不动钱：这里是订单实付金额，供客服判断诉求金额是否合理 -->
          <span class="label">{{ t('service_ticket.buyer_order_amount') }}</span>
          <span class="value amount">¥{{ ticket.orderPayAmount != null ? ticket.orderPayAmount : '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.create_time') }}</span>
          <span class="value">{{ formatDateTime(ticket.createTime) }}</span>
          <span class="label">{{ t('service_ticket.operate_time') }}</span>
          <span class="value">{{ formatDateTime(ticket.operateTime) }}</span>
        </div>
      </div>

      <!-- 裁定标准原文：与买家申诉页看到的是后端下发的同一段，防止口径分叉 -->
      <div class="standard-block">
        <div class="standard-title">{{ t('service_ticket.cs_standard') }}</div>
        <div class="standard-text">{{ meta.csStandard }}</div>
        <div class="standard-tip">{{ t('service_ticket.cs_standard_tip') }}</div>
      </div>

      <!-- 已裁定且放行：把「一次性 + 7 天有效期」说清楚，避免买家以为可以反复用 -->
      <el-alert
        v-if="allowed"
        :title="t('service_ticket.authorization_tip', { days: authorizationDays })"
        type="success"
        :closable="false"
        show-icon
        class="tip-alert"
      />

      <!-- 已处理（已裁定 / 已取消）：表单禁用并展示既有结论 -->
      <el-alert
        v-if="locked"
        :title="t('service_ticket.already_resolved')"
        type="info"
        :closable="false"
        show-icon
        class="tip-alert"
      />

      <el-form :model="form" label-width="120px" class="resolve-form">
        <el-form-item :label="t('service_ticket.resolution')" required>
          <el-radio-group v-model="form.resolution" :disabled="locked">
            <el-radio :value="1">{{ t('service_ticket.resolution_allow') }}</el-radio>
            <el-radio :value="2">{{ t('service_ticket.resolution_reject') }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item :label="t('service_ticket.cs_remark')">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :disabled="locked"
          />
        </el-form-item>

        <el-form-item v-if="!locked">
          <el-button type="primary" :loading="submitting" @click="submit">
            {{ t('service_ticket.resolve_submit') }}
          </el-button>
          <el-button @click="goBack">{{ t('common.cancel') }}</el-button>
        </el-form-item>
        <el-form-item v-else>
          <el-button @click="goBack">{{ t('service_ticket.back_list') }}</el-button>
        </el-form-item>
      </el-form>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { getServiceTicketDetail, getServiceTicketMeta, resolveServiceTicket } from '@/api/serviceTicket'
import { getImageUrl } from '@/utils/image'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()

const loading = ref(false)
const submitting = ref(false)
const ticket = ref({})
const meta = ref({})
// 【测试报告#3】工单不存在 / 已删除（后端 data 为空）时置位，用于渲染空态
const notFound = ref(false)
// 原因字典：后端下发（code → 文案），前端不硬编码第二套口径
const reasons = ref({})

const form = ref({
  resolution: 1,
  remark: ''
})

const images = computed(() => ticket.value.images || [])
const imageList = computed(() => images.value.map((u) => getImageUrl(u)))

// status !== 0 即已裁定 / 已取消：表单必须禁用（后端也只允许裁定待处理工单）。
// 【测试报告#3】必须先判空：工单不存在时 ticket 是空对象，Number(undefined) = NaN，
// `NaN !== 0` 为 true 会让「已裁定，不可重复处理」误报。
const locked = computed(() => {
  const status = ticket.value.status
  if (status == null || status === '') return false
  return Number(status) !== 0
})
// 已裁定且同意放行
const allowed = computed(
  () => Number(ticket.value.status) === 1 && Number(ticket.value.resolution) === 1
)

const authorizationDays = computed(() => Number(meta.value.csAuthorizationDays) || 7)

const windowText = computed(() => {
  const n = Number(ticket.value.returnWindowDays)
  if (!Number.isFinite(n)) {
    return '-'
  }
  // 与工作台列同一口径：return_window_text 是标签，天数补在其后；0 = 不支持退货退款
  return `${t('service_ticket.return_window_text')} ${n > 0 ? n : 0}`
})

const statusText = computed(() => {
  const status = Number(ticket.value.status)
  if (status === 0) {
    return t('service_ticket.status_pending')
  }
  if (status === 1) {
    return t('service_ticket.status_resolved')
  }
  return t('service_ticket.status_cancelled')
})

const statusType = computed(() => {
  const status = Number(ticket.value.status)
  if (status === 0) {
    return 'warning'
  }
  if (status === 1) {
    return Number(ticket.value.resolution) === 2 ? 'danger' : 'success'
  }
  return 'info'
})

const reasonText = (code) => {
  if (!code) {
    return '-'
  }
  return reasons.value[code] || code
}

const loadData = async () => {
  loading.value = true
  try {
    const [ticketRes, metaRes] = await Promise.all([
      getServiceTicketDetail(route.params.id),
      getServiceTicketMeta()
    ])
    // 【测试报告#3】data 为空 = 工单不存在或已删除。原先行数直接 `|| {}` 硬接，
    // 空对象会一路渲染成满屏 "-" 占位，并因 locked 误判为「已裁定」。
    // 这里显式标记 notFound，由模板切到空态分支，不再回填裁定表单。
    notFound.value = !ticketRes.data || ticketRes.data.id == null
    ticket.value = ticketRes.data || {}
    meta.value = metaRes.data || {}
    reasons.value = meta.value.reasons || {}
    if (notFound.value) {
      return
    }
    // 回填既有裁定：已处理工单只读展示，未处理时给一个默认选项（同意）——
    // 但驳回必填说明的校验仍然生效，不存在「一键默认通过」的捷径
    form.value.resolution = Number(ticket.value.resolution) === 2 ? 2 : 1
    form.value.remark = ticket.value.csRemark || ''
  } catch (e) {
    // request 拦截器已统一弹窗（无权访问会提示「客服工单不存在或无权访问」）
    console.error('加载客服工单详情失败', e)
    // 请求本身失败（404/403/网络）也走空态，避免页面停在初始的空对象上
    notFound.value = true
  } finally {
    loading.value = false
  }
}

const submit = async () => {
  if (Number(form.value.resolution) === 2 && !String(form.value.remark || '').trim()) {
    // 后端对「驳回但无依据」直接 400；前端先拦一道，省掉一次无谓往返
    ElMessage.warning(t('service_ticket.remark_required_for_reject'))
    return
  }
  submitting.value = true
  try {
    await resolveServiceTicket(route.params.id, {
      resolution: Number(form.value.resolution),
      remark: form.value.remark
    })
    ElMessage.success(t('service_ticket.resolve_success'))
    router.push('/kefu')
  } catch (e) {
    // 常见失败：工单已被他人裁定 / 已取消（后端提示「请刷新后查看」），
    // 拦截器已弹窗，这里重新拉一次让页面回到真实状态
    console.error('裁定客服工单失败', e)
    await loadData()
  } finally {
    submitting.value = false
  }
}

const goBack = () => router.push('/kefu')

onMounted(loadData)

/**
 * 只重拉 meta + 原因字典（切语言时用）。
 *
 * 【为什么不直接 watch(loadData)】loadData 会把 `form.resolution` 与
 * `form.remark` 用工单里的既有值覆盖回去 —— 客服正写着裁定备注时切语言，
 * 那段文字会被清掉。这里只刷与语言相关的 meta。
 */
const loadMeta = async () => {
  try {
    const res = await getServiceTicketMeta()
    meta.value = res.data || {}
    reasons.value = meta.value.reasons || {}
  } catch (e) {
    console.error('刷新客服裁定标准失败', e)
  }
}

watch(locale, () => { loadMeta() })
</script>

<style scoped>
.kefu-ticket-detail-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.detail-block {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: var(--bg-secondary);
}

.detail-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 5px 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.detail-row .label {
  flex: none;
  min-width: 76px;
  color: var(--text-muted);
}

.detail-row .value {
  color: var(--text-primary);
  font-weight: 500;
  margin-right: 20px;
  word-break: break-all;
}

.detail-row .multi-line {
  white-space: pre-line;
  font-weight: normal;
}

.detail-row .overdue {
  color: #f56c6c;
  font-weight: bold;
}

.detail-row .amount {
  color: #f56c6c;
  font-weight: bold;
}

.voucher-image {
  width: 84px;
  height: 84px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  margin-right: 8px;
  cursor: pointer;
}

.standard-block {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: var(--bg-card);
}

.standard-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
}

/* pre-line：裁定标准是多行原文，换行必须原样保留 */
.standard-text {
  white-space: pre-line;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.standard-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
}

.tip-alert {
  margin-bottom: 16px;
}

.resolve-form {
  max-width: 720px;
}
</style>
