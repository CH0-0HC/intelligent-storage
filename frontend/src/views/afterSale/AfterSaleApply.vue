<template>
  <div class="after-sale-apply-page">
    <el-card v-loading="loading">
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('after_sale.apply_title') }}</span>
          <el-button size="small" @click="goBack">{{ t('common.back') }}</el-button>
        </div>
      </template>

      <!-- 【客服通道】已超期但可申诉（后端 csRequired=true）：
           这里不能显示成「不可申请」把用户堵死，而是给「联系客服」的活路 ——
           申请一张客服工单，客服裁定通过后 7 天内一次性放行，仍可回来提交售后 -->
      <el-alert
        v-if="csBlocked"
        :title="t('after_sale.cs_required_title')"
        type="warning"
        :closable="false"
        show-icon
        class="window-alert"
      >
        <template #default>
          <div class="alert-body">
            <!-- hint 用后端原文，前端不重写口径 -->
            <div>{{ blockedHint }}</div>
            <div class="alert-actions">
              <el-button type="primary" size="small" @click="goContactCs">
                {{ t('service_ticket.contact') }}
              </el-button>
              <el-button type="primary" link @click="goBack">{{ t('after_sale.go_orders') }}</el-button>
            </div>
          </div>
        </template>
      </el-alert>

      <!-- 其余不可申请（如商品不支持退货退款、订单状态不满足）：保持原有警告分支不动 -->
      <el-alert
        v-else-if="blocked"
        :title="blockedHint"
        type="warning"
        :closable="false"
        show-icon
        class="window-alert"
      >
        <template #default>
          <div class="alert-body">
            <div>{{ blockedHint }}</div>
            <div class="alert-actions">
              <el-button v-if="eligibility.latestAfterSaleId" type="primary" link @click="goDetail(eligibility.latestAfterSaleId)">
                {{ t('after_sale.view') }}
              </el-button>
              <el-button type="primary" link @click="goBack">{{ t('after_sale.go_orders') }}</el-button>
            </div>
          </div>
        </template>
      </el-alert>

      <template v-else>
        <!-- 时效提示：口径改为读后端 returnWindowDays（商家按商品配置的退货期限，本订单取最小值），
             不再写死 15 天；只有商品没配置过期限时才回落到旧的兜底文案 -->
        <el-alert type="info" :closable="false" show-icon class="window-alert">
          <template #default>
            <div>{{ windowRuleText }}</div>
            <div class="window-sub">
              <span v-if="eligibility.daysLeft > 0">{{ t('after_sale.days_left', { days: eligibility.daysLeft }) }}</span>
              <span v-if="eligibility.noReasonDaysLeft > 0" class="window-sub-item">
                {{ t('after_sale.no_reason_left', { days: eligibility.noReasonDaysLeft }) }}
              </span>
              <span v-else class="window-sub-item">{{ t('after_sale.no_reason_over') }}</span>
            </div>
          </template>
        </el-alert>

        <!-- 客服已裁定放行：授权是「一次性 + 有截止时间」的，必须把截止日期显式暴露出来，
             否则用户不知道过了这天要重新申诉（后端 7 天有效、用完即失效） -->
        <el-alert
          v-if="csApproved"
          type="success"
          :closable="false"
          show-icon
          class="window-alert"
          :title="t('after_sale.cs_approved', { date: csDeadline })"
        />

        <!-- 订单摘要 -->
        <div class="order-summary">
          <div class="summary-row">
            <span class="label">{{ t('after_sale.order_no') }}</span>
            <span class="value">{{ order.orderNo }}</span>
            <span class="label">{{ t('after_sale.complete_time') }}</span>
            <span class="value">{{ formatDateTime(order.completeTime) }}</span>
          </div>
          <div class="order-items">
            <div class="select-hint">{{ t('after_sale.select_products') }}</div>
            <div v-for="item in order.items || []" :key="item.id" class="order-item">
              <el-checkbox
                v-if="selections[item.id]"
                v-model="selections[item.id].checked"
                class="item-check"
              />
              <img :src="getImageUrl(item.productImage)" class="item-image" />
              <div class="item-name">{{ item.productName }}</div>
              <div class="item-qty">x{{ item.quantity }}</div>
              <el-input-number
                v-if="selections[item.id] && selections[item.id].checked"
                v-model="selections[item.id].quantity"
                :min="1"
                :max="Number(item.quantity) || 1"
                :precision="0"
                size="small"
                controls-position="right"
                class="item-qty-input"
                :aria-label="t('after_sale.item_qty')"
              />
              <div class="item-price">¥{{ item.price }}</div>
            </div>
          </div>
          <div class="summary-row total-row">
            <span class="label">{{ t('after_sale.pay_amount') }}</span>
            <span class="amount">¥{{ order.payAmount }}</span>
          </div>
        </div>

        <el-form :model="form" label-width="100px" class="apply-form">
          <el-form-item :label="t('after_sale.type')" required>
            <el-radio-group v-model="form.type">
              <el-radio-button :value="1">{{ t('after_sale.type_refund_only') }}</el-radio-button>
              <el-radio-button :value="2">{{ t('after_sale.type_return_refund') }}</el-radio-button>
              <el-radio-button :value="3">{{ t('after_sale.type_exchange') }}</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item :label="t('after_sale.reason')" required>
            <el-select v-model="form.reasonCode" :placeholder="t('after_sale.reason_placeholder')" style="width: 320px">
              <el-option
                v-for="(text, code) in reasons"
                :key="code"
                :label="text"
                :value="code"
                :disabled="code === 'no_reason' && noReasonDisabled"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="form.type !== 3" :label="t('after_sale.amount')" required>
            <el-input-number
              v-model="form.refundAmount"
              :min="0.01"
              :max="maxRefund"
              :precision="2"
              :step="1"
              @input="refundTouched = true"
              @change="refundTouched = true"
            />
            <span class="amount-tip">{{ t('after_sale.amount_tip', { amount: maxRefund }) }}</span>
          </el-form-item>

          <el-form-item :label="t('after_sale.description')">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              :placeholder="t('after_sale.description_placeholder')"
            />
          </el-form-item>

          <el-form-item :label="t('after_sale.images')">
            <div class="upload-block">
              <!-- el-upload 用自己的 XHR，不经过 axios 拦截器，
                   必须显式传 headers，且必须用 buildAuthHeader()（裸 token，勿加 Bearer） -->
              <el-upload
                :action="uploadUrl"
                :headers="uploadHeaders"
                :show-file-list="false"
                :before-upload="beforeUpload"
                :on-success="onUploadSuccess"
                accept="image/*"
              >
                <el-button type="primary" plain size="small">＋ {{ t('after_sale.images') }}</el-button>
              </el-upload>
              <span class="amount-tip">{{ t('after_sale.images_tip') }}</span>
            </div>
            <div v-if="form.images.length" class="thumb-list">
              <div v-for="(url, idx) in form.images" :key="url" class="thumb-item">
                <img :src="getImageUrl(url)" />
                <el-icon class="thumb-remove" @click="removeImage(idx)"><Close /></el-icon>
              </div>
            </div>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submit">{{ t('after_sale.submit') }}</el-button>
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
  applyAfterSale,
  getAfterSaleReasons,
  getOrderEligibility
} from '@/api/afterSale'
import { getImageUrl } from '@/utils/image'
import { buildAuthHeader } from '@/utils/request'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()

const loading = ref(false)
const submitting = ref(false)
const order = ref({})
const eligibility = ref({})
const reasons = ref({})

const form = ref({
  orderId: null,
  type: 1,
  reasonCode: '',
  description: '',
  images: [],
  refundAmount: null
})

// 【P1-6】按商品申请：每行勾选 + 数量。key = order_item.id。
// 一个都没勾选时提交体**不带 items**（保持旧整单语义逐字不变）。
const selections = ref({})

// 【B5-4】用户是否手动改过退款金额。仅在退款金额输入框的 @input/@change 置 true；
// el-input-number 的 modelValue 程序化更新不会触发这两个事件，故 watch 自动回填不会误标。
const refundTouched = ref(false)

const selectedRows = computed(() =>
  (order.value.items || [])
    .filter((it) => selections.value[it.id]?.checked)
    .map((it) => ({
      orderItemId: it.id,
      quantity: Number(selections.value[it.id]?.quantity || 1),
      price: Number(it.price || 0)
    }))
)

const uploadUrl = '/api/upload/image'
// computed：token 重新登录后会变化，普通对象只在 setup 求值一次会带旧 token
const uploadHeaders = computed(() => buildAuthHeader())

// 【本次改造】blocked 拆成两种形态（原来是「eligible !== true 就整页警告」，超期会变成死路）：
//   1) csBlocked：已超期但可申诉（后端 csRequired=true）→ 引导「联系客服」，不是不可申请；
//   2) blocked  ：其余真正不可申请的情况 → 保持原有警告分支。
// 另外 csApproved=true（客服裁定通过、7 天内一次性放行）时后端会允许继续提交，
// 前端必须放行到申请表单，否则用户申诉成功后仍然卡在「不可申请」页。
const csApproved = computed(() => eligibility.value.csApproved === true)
const csBlocked = computed(
  () => eligibility.value.eligible !== true && eligibility.value.csRequired === true && !csApproved.value
)
const blocked = computed(
  () => eligibility.value.eligible !== true && !csBlocked.value && !csApproved.value
)
const blockedHint = computed(() => eligibility.value.hint || t('after_sale.status_unknown'))
// 无理由的两个维度：时间（剩余无理由天数）与商品政策（商家是否承诺无理由）
const noReasonAvailable = computed(() => Number(eligibility.value.noReasonDaysLeft || 0) > 0)
// 政策维度：只有后端明确 false 才视为「不接无理由」；undefined（旧数据没这字段）= 不限制
const noReasonPolicyAllowed = computed(() => eligibility.value.returnNoReasonAllowed !== false)
// 任一维度为「否」就禁用「无理由」选项：这是后端会硬拦的规则，前端必须同步，
// 否则用户能选中、点了提交才被拒（白填一次表单）
const noReasonDisabled = computed(() => !noReasonAvailable.value || !noReasonPolicyAllowed.value)

// 退货期限文案：口径全部来自后端，前端不再写死「15 天」。
// 优先级：0（不支持退货退款）> 已配置且 >0（展示本订单适用天数）> 未配置（沿用旧兜底文案）
const windowRuleText = computed(() => {
  const e = eligibility.value
  const days = e.returnWindowDays
  // 注意不能直接 Number(days) === 0：Number(null) 也是 0，会把「未配置」误判成「不支持退货」
  if (days !== null && days !== undefined && Number(days) === 0) {
    return t('after_sale.window_none')
  }
  if (e.returnWindowConfigured === true && Number(days) > 0) {
    return t('after_sale.window_product_rule', { days: Number(days) })
  }
  return t('after_sale.window_tip')
})

// 客服授权的截止时间：未放行/无工单时后端可能给空，用 '-' 占位避免渲染出 undefined
const csDeadline = computed(() => eligibility.value.csAuthorizationDeadline || '-')

const maxRefund = computed(() => {
  const pay = Number(order.value.payAmount || 0)
  const picked = selectedRows.value
  if (picked.length) {
    // 选中商品时：Σ(单价 × 选中数量)，与订单实付取小
    const sum = picked.reduce((s, r) => s + r.price * r.quantity, 0)
    const cap = Math.min(sum, pay > 0 ? pay : sum)
    return cap > 0 ? Number(cap.toFixed(2)) : 0.01
  }
  // 未选中：保持旧值，避免 C1 的 400/200 边界漂移
  return pay > 0 ? pay : 0.01
})

// 【B5-4】退款金额随勾选联动：未手动改过 ⇒ 跟随 maxRefund（勾选=所选商品合计∩实付；
// 全部取消=整单实付）；已手动改过 ⇒ 仅在超出 maxRefund 时下调，避免提交必然 400。
watch(selectedRows, () => {
  const cap = maxRefund.value
  if (!refundTouched.value) {
    form.value.refundAmount = cap
  } else if (Number(form.value.refundAmount || 0) > cap) {
    form.value.refundAmount = cap
  }
})

const loadData = async () => {
  const orderId = route.params.orderId
  loading.value = true
  try {
    const [orderRes, eligRes, reasonRes] = await Promise.all([
      getOrderById(orderId),
      getOrderEligibility(orderId),
      getAfterSaleReasons()
    ])
    order.value = orderRes.data || {}
    eligibility.value = eligRes.data || {}
    reasons.value = reasonRes.data || {}
    // 默认全部不勾选（保持整单语义）；数量上限 = 该明细可退数量
    const initSelections = {}
    for (const it of order.value.items || []) {
      initSelections[it.id] = { checked: false, quantity: Number(it.quantity) || 1 }
    }
    selections.value = initSelections
    form.value.orderId = Number(orderId)
    form.value.refundAmount = maxRefund.value
  } catch (e) {
    // request 拦截器已统一提示（403 会给出「订单不存在或无权访问」），这里只兜状态
    eligibility.value = { eligible: false, hint: t('after_sale.status_unknown') }
  } finally {
    loading.value = false
  }
}

const beforeUpload = (file) => {
  const isImage = file.type.startsWith('image/')
  if (!isImage) {
    ElMessage.warning(t('after_sale.images_tip'))
    return false
  }
  if (form.value.images.length >= 6) {
    ElMessage.warning(t('after_sale.images_tip'))
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
    ElMessage.warning(t('after_sale.reason_placeholder'))
    return
  }
  submitting.value = true
  try {
    const payload = { ...form.value }
    if (payload.type === 3) {
      payload.refundAmount = 0
    }
    // 仅在勾选了商品时带 items；未勾选时提交体与旧整单请求逐字一致（无 items 键）
    const picked = selectedRows.value
    if (picked.length) {
      payload.items = picked.map((r) => ({ orderItemId: r.orderItemId, quantity: r.quantity }))
    }
    const res = await applyAfterSale(payload)
    ElMessage.success(res.message || t('after_sale.submit_success'))
    const id = res.data?.id
    if (id) {
      router.replace(`/after-sale/${id}`)
    } else {
      router.replace('/after-sale')
    }
  } catch (e) {
    // 后端会返回可读原因（如「已超过 7 天无理由」），拦截器已弹窗，此处刷新资格状态
    await loadData()
  } finally {
    submitting.value = false
  }
}

const goBack = () => router.push('/orders')
const goDetail = (id) => router.push(`/after-sale/${id}`)
// 【客服通道】申诉表单交给 /service-ticket/apply/:orderId，本页不重复实现一套表单，
// 避免两处入口的字段/校验漂移
const goContactCs = () => router.push(`/service-ticket/apply/${route.params.orderId}`)

onMounted(loadData)

/**
 * 只重拉原因字典（切语言时用）。
 *
 * 【为什么不直接 watch(loadData)】loadData 会顺带把「按商品勾选」与
 * `refundAmount` 重置回默认值 —— 用户在表单里勾了一半再点语言切换，
 * 已勾选的商品会凭空消失。字典是唯一与语言相关的部分，只刷它。
 */
const loadReasons = async () => {
  try {
    const res = await getAfterSaleReasons()
    reasons.value = res.data || {}
  } catch (e) {
    console.error('刷新售后原因字典失败', e)
  }
}

watch(locale, () => { loadReasons() })
</script>

<style scoped>
.after-sale-apply-page {
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

.window-alert {
  margin-bottom: 16px;
}

.alert-body .alert-actions {
  margin-top: 8px;
}

.window-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.window-sub-item {
  margin-left: 12px;
}

/* 【测试报告#7】两条时效提示原先只靠上面这条 margin-left 分隔，读起来粘连成一串
   （「剩余 15 天可申请7天无理由还剩 7 天」）。补一个显式分隔符。
   用 `span + span` 保证只在前面还有一段时才出现 —— daysLeft ≤ 0 时第二段是行首，
   不会冒出一个孤立的「·」。 */
.window-sub > span + span.window-sub-item::before {
  content: '·';
  margin-right: 8px;
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

.total-row .amount {
  color: #f56c6c;
  font-size: 18px;
  font-weight: bold;
}

.order-items {
  border-top: 1px dashed var(--border-color);
  padding-top: 8px;
  margin-bottom: 8px;
}

.select-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 4px;
}

.item-check {
  margin-right: 2px;
}

.item-qty-input {
  width: 110px;
}

.order-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
}

.item-image {
  width: 44px;
  height: 44px;
  object-fit: cover;
  border-radius: 4px;
  background: var(--bg-card);
}

.item-name {
  flex: 1;
  color: var(--text-primary);
}

.item-qty,
.item-price {
  color: var(--text-muted);
}

.amount-tip {
  margin-left: 10px;
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
