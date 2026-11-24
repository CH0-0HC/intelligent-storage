<template>
  <div class="service-ticket-apply-page">
    <el-card v-loading="loading">
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('service_ticket.apply_title') }}</span>
          <el-button size="small" @click="goBack">{{ t('common.back') }}</el-button>
        </div>
      </template>

      <!-- 不可提交：把后端的 hint 原样展示。
           口径由后端 check 统一给出（「仍在期限内请直接申请售后」/「已有待裁定工单」/
           「已有可用放行授权」等），前端不重写、不猜原因。 -->
      <el-alert
        v-if="blocked"
        :title="check.hint || t('service_ticket.not_allowed')"
        type="warning"
        :closable="false"
        show-icon
        class="block-alert"
      >
        <template #default>
          <div class="alert-body">
            <div>{{ check.hint || t('service_ticket.not_allowed') }}</div>
            <div class="alert-actions">
              <!-- 已有工单（待裁定 / 已放行）时给一条直达记录页的路径 -->
              <el-button v-if="check.existingTicketId" type="primary" link @click="goMyTickets">
                {{ t('service_ticket.view') }}
              </el-button>
              <el-button type="primary" link @click="goBack">{{ t('service_ticket.back_orders') }}</el-button>
            </div>
          </div>
        </template>
      </el-alert>

      <template v-else>
        <!-- 订单摘要：超期事实要先摆出来，买家才知道自己在申诉什么 -->
        <div class="order-summary">
          <div class="summary-row">
            <span class="label">{{ t('service_ticket.order_no') }}</span>
            <span class="value">{{ order.orderNo || check.orderNo || '-' }}</span>
            <span class="label">{{ t('service_ticket.return_window') }}</span>
            <span class="value">{{ windowText }}</span>
            <span class="label">{{ t('service_ticket.days_overdue') }}</span>
            <span class="value overdue">{{ overdueDays }}</span>
          </div>
          <div class="summary-row">
            <span class="label">{{ t('service_ticket.product') }}</span>
            <span class="value">
              {{ currentProductName }}
              <span v-if="checking" class="checking-tip">{{ t('service_ticket.checking') }}</span>
            </span>
          </div>
          <!-- 后端 hint 里已经写明「超了多少天、请补充什么凭证」，原样展示 -->
          <el-alert
            v-if="check.hint"
            type="info"
            :closable="false"
            show-icon
            class="hint-alert"
            :title="check.hint"
          />
        </div>

        <!-- 裁定标准原文：让买家提交前就知道依据，避免「先提交再被驳回」 -->
        <div class="standard-block">
          <div class="standard-title">{{ t('service_ticket.cs_standard') }}</div>
          <div class="standard-text">{{ meta.csStandard }}</div>
          <div class="standard-tip">{{ t('service_ticket.cs_standard_tip') }}</div>
        </div>

        <el-form :model="form" label-width="110px" class="apply-form">
          <!-- 多商品订单：按商品申诉，期限按该商品自己的退货期限算。
               只有一个商品时自动带上，不显示下拉（backend 会按 orderItemId 推导 productId）。 -->
          <el-form-item v-if="hasMultipleItems" :label="t('service_ticket.select_product')" required>
            <el-select
              v-model="selectedItemKey"
              style="width: 320px"
              @change="onProductChange"
            >
              <el-option
                v-for="item in items"
                :key="itemKey(item)"
                :label="item.productName"
                :value="itemKey(item)"
              />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('service_ticket.reason')" required>
            <el-select
              v-model="form.reasonCode"
              :placeholder="t('service_ticket.reason')"
              style="width: 320px"
            >
              <!-- 原因字典来自后端（meta.reasons），避免前端硬编码两套文案 -->
              <el-option
                v-for="(text, code) in reasons"
                :key="code"
                :label="text"
                :value="code"
              />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('service_ticket.description')">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>

          <el-form-item :label="t('service_ticket.images')">
            <div class="upload-block">
              <!-- el-upload 走自己的 XHR，不经过 axios 拦截器，
                   必须显式传 headers，且必须是 buildAuthHeader()（裸 token，勿加 Bearer） -->
              <el-upload
                :action="uploadUrl"
                :headers="uploadHeaders"
                :show-file-list="false"
                :before-upload="beforeUpload"
                :on-success="onUploadSuccess"
                accept="image/*"
              >
                <el-button type="primary" plain size="small">＋ {{ t('service_ticket.images') }}</el-button>
              </el-upload>
            </div>
            <div v-if="form.images.length" class="thumb-list">
              <div v-for="(url, idx) in form.images" :key="url" class="thumb-item">
                <img :src="getImageUrl(url)" />
                <el-icon class="thumb-remove" @click="removeImage(idx)"><Close /></el-icon>
              </div>
            </div>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submit">
              {{ t('service_ticket.submit') }}
            </el-button>
            <el-button @click="goBack">{{ t('common.cancel') }}</el-button>
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
import { Close } from '@element-plus/icons-vue'
import { getOrderById } from '@/api/order'
import {
  getServiceTicketMeta,
  checkServiceTicket,
  submitServiceTicket
} from '@/api/serviceTicket'
import { getImageUrl } from '@/utils/image'
import { buildAuthHeader } from '@/utils/request'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()

const loading = ref(false)
const submitting = ref(false)
// 切换商品明细时的局部 loading：只影响期限信息的刷新，不整页遮罩
const checking = ref(false)

const order = ref({})
const meta = ref({})
const check = ref({})

// 【P1-6 同款约定】按商品申诉：orderItemId 与 productId 成对提交。
// 整单申诉（只有一件商品 / 未选择）时不带这两个字段，后端按整单最严一档算期限。
const selectedItemKey = ref('')

const form = ref({
  reasonCode: '',
  description: '',
  images: []
})

const uploadUrl = '/api/upload/image'
// computed：token 重新登录后会变化，普通对象只在 setup 求值一次会带旧 token
const uploadHeaders = computed(() => buildAuthHeader())

// allowed 未返回（例如请求失败）时按「不可提交」处理：宁可少给一个入口，
// 也不能让买家填完表单才被后端拒绝
const blocked = computed(() => check.value.allowed !== true)
const reasons = computed(() => meta.value.reasons || {})
const items = computed(() => check.value.items || [])
const hasMultipleItems = computed(() => items.value.length > 1)

const itemKey = (item) => `${item.orderItemId || ''}:${item.productId || ''}`

const currentItem = computed(() =>
  items.value.find((it) => itemKey(it) === selectedItemKey.value) || null
)

const currentProductName = computed(() => {
  if (currentItem.value) {
    return currentItem.value.productName || '-'
  }
  return check.value.productName || '-'
})

// 期限文案优先用后端下发的 returnWindowText（口径唯一，形如「确认收货后 15 天内」）；
// 仅当某次响应没带该字段时才退化：return_window_text 是**标签**（不含 {days} 占位符），
// 因此把天数补在标签之后，绝不出现「有标签没数字」的空壳。
const windowText = computed(() => {
  if (check.value.returnWindowText) {
    return check.value.returnWindowText
  }
  const days = Number(check.value.returnWindowDays)
  if (!Number.isFinite(days)) {
    return '-'
  }
  return `${t('service_ticket.return_window_text')} ${days > 0 ? days : 0}`
})

const overdueDays = computed(() => {
  const n = Number(check.value.daysOverdue)
  return Number.isFinite(n) && n > 0 ? n : 0
})

/**
 * 拉取可提交性 / 期限信息。
 *
 * 【为什么每次切商品都要重查】退货期限是**商品级**的：同一订单里 A 商品 7 天、
 * B 商品 30 天，整单口径只能取最严一档，按商品申诉时才拿得到该商品自己的期限。
 * 不重查就会出现「下拉选了 B，页面却显示 A 的期限」。
 */
const loadCheck = async (orderItemId, productId) => {
  const params = { orderId: route.params.orderId }
  if (orderItemId) {
    params.orderItemId = orderItemId
  }
  if (productId) {
    params.productId = productId
  }
  const res = await checkServiceTicket(params)
  check.value = res.data || {}
  return check.value
}

const loadData = async () => {
  const orderId = route.params.orderId
  loading.value = true
  try {
    // 三个请求互不依赖，并行发出
    // 三个请求互不依赖，并行发出。
    // 【测试报告#9·/service-ticket/apply/9999】改用 Promise.allSettled：
    // 订单详情对不存在的订单会返回 400，原先 Promise.all 一旦 reject，
    // 就把**已经拿到的 check 结果一起丢掉**，catch 里兜成 { allowed: false }（没有 hint），
    // 页面于是只剩一句笼统的「当前无法提交客服工单」——而后端明明已经给出了
    // 「订单不存在或无权访问」。allSettled 让各自的成功结果都留下。
    const [metaRes, orderRes, checkRes] = await Promise.allSettled([
      getServiceTicketMeta(),
      getOrderById(orderId),
      checkServiceTicket({ orderId })
    ])
    meta.value = metaRes.status === 'fulfilled' ? metaRes.value.data || {} : {}
    order.value = orderRes.status === 'fulfilled' ? orderRes.value.data || {} : {}
    check.value = checkRes.status === 'fulfilled' ? (checkRes.value.data || {}) : {}

    // 连 check 本身都失败（网络断 / 401）时才兜成「不可提交」，并补一句可读提示
    if (checkRes.status === 'rejected') {
      check.value = { allowed: false, hint: t('service_ticket.not_allowed') }
    }

    // 只有一个商品时自动选中，提交体才会带上 orderItemId / productId；
    // 多个商品时留空，由买家在下拉里选（后端此时按整单最严一档先给一版期限）
    const list = items.value
    if (list.length === 1) {
      selectedItemKey.value = itemKey(list[0])
    }
  } catch (e) {
    // request 拦截器已统一弹窗，这里只兜状态：按「不可提交」渲染
    console.error('加载客服申诉页数据失败', e)
    check.value = { allowed: false, hint: t('service_ticket.not_allowed') }
  } finally {
    loading.value = false
  }
}

/** 切换申诉商品：重查该商品的退货期限与超期天数 */
const onProductChange = async () => {
  const item = currentItem.value
  if (!item) {
    return
  }
  checking.value = true
  try {
    await loadCheck(item.orderItemId, item.productId)
  } catch (e) {
    console.error('刷新商品退货期限失败', e)
  } finally {
    checking.value = false
  }
}

const beforeUpload = (file) => {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning(t('common.warning'))
    return false
  }
  if (form.value.images.length >= 6) {
    ElMessage.warning(t('common.warning'))
    return false
  }
  return true
}

const onUploadSuccess = (res) => {
  if (res && res.code === 200 && res.data?.url) {
    form.value.images.push(res.data.url)
  } else {
    ElMessage.error(res?.message || t('common.warning'))
  }
}

const removeImage = (idx) => {
  form.value.images.splice(idx, 1)
}

const submit = async () => {
  if (!form.value.reasonCode) {
    ElMessage.warning(t('service_ticket.reason'))
    return
  }
  submitting.value = true
  try {
    const payload = {
      orderId: Number(route.params.orderId),
      reasonCode: form.value.reasonCode,
      description: form.value.description,
      images: form.value.images
    }
    // 仅按商品申诉时携带：整单申诉保持 payload 里没有这两个键
    const item = currentItem.value
    if (item) {
      payload.orderItemId = item.orderItemId
      payload.productId = item.productId
    }
    await submitServiceTicket(payload)
    ElMessage.success(t('service_ticket.submit_success'))
    router.push('/service-ticket')
  } catch (e) {
    // 拦截器已统一弹窗（后端会给出可读原因，如「已超出期限」「请选择有效原因」），
    // 这里只兜底日志，并刷新一次可提交性，避免页面停留在「看起来能提」的状态
    console.error('提交客服工单失败', e)
    try {
      const item = currentItem.value
      await loadCheck(item?.orderItemId, item?.productId)
    } catch (inner) {
      console.error('刷新可提交性失败', inner)
    }
  } finally {
    submitting.value = false
  }
}

const goBack = () => router.push('/orders')
// 已有工单时没有买家侧详情路由，统一回「我的客服记录」列表
const goMyTickets = () => router.push('/service-ticket')

onMounted(loadData)

/**
 * 只重拉 meta（切语言时用）。
 *
 * 【为什么不直接 watch(loadData)】loadData 还会重置 `selectedItemKey`
 * 等表单状态；用户填到一半切语言不该丢内容。meta 里才是随语言变的
 * 诉求原因字典与「客服裁定标准」原文。
 */
const loadMeta = async () => {
  try {
    const res = await getServiceTicketMeta()
    meta.value = res.data || {}
  } catch (e) {
    console.error('刷新客服申诉 meta 失败', e)
  }
}

watch(locale, () => { loadMeta() })
</script>

<style scoped>
.service-ticket-apply-page {
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

.block-alert {
  margin-bottom: 16px;
}

.alert-body .alert-actions {
  margin-top: 8px;
}

.order-summary {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 20px;
  background: var(--bg-secondary);
}

.summary-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.summary-row .label {
  color: var(--text-muted);
}

.summary-row .value {
  color: var(--text-primary);
  font-weight: 500;
  margin-right: 18px;
}

.summary-row .overdue {
  color: #f56c6c;
  font-weight: bold;
}

.checking-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-muted);
  font-weight: normal;
}

.hint-alert {
  margin-top: 4px;
}

.standard-block {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 20px;
  background: var(--bg-card);
}

.standard-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
}

/* pre-line：csStandard 是多行原文，换行必须原样保留 */
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

.upload-block {
  display: flex;
  align-items: center;
  gap: 10px;
}

.thumb-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.thumb-item {
  position: relative;
  width: 72px;
  height: 72px;
}

.thumb-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--border-color);
}

.thumb-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  background: var(--bg-card);
  border-radius: 50%;
  padding: 2px;
  cursor: pointer;
  color: #f56c6c;
  border: 1px solid var(--border-color);
}
</style>
