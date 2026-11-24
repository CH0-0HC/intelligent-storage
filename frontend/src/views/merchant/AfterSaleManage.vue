<template>
  <div class="after-sale-manage">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('after_sale.manage_title') }}</span>
          <div class="header-right">
            <el-select v-model="statusFilter" size="small" style="width: 140px" @change="loadList">
              <el-option :label="t('after_sale.all')" :value="-1" />
              <el-option :label="t('after_sale.status_pending')" :value="0" />
              <el-option :label="t('after_sale.status_agreed')" :value="1" />
              <el-option :label="t('after_sale.status_finished')" :value="2" />
              <el-option :label="t('after_sale.status_rejected')" :value="3" />
              <el-option :label="t('after_sale.status_cancelled')" :value="4" />
            </el-select>
            <el-button type="primary" size="small" @click="loadList">{{ t('common.refresh') }}</el-button>
          </div>
        </div>
      </template>

      <!-- 【R13】演示环境边界提示（已随 R20 i18n 化，文案复用 after_sale.demo_notice）。 -->
      <el-alert
        type="info"
        show-icon
        style="margin-bottom: 12px"
        :title="t('after_sale.demo_notice')"
      />

      <el-table :data="list" stripe v-loading="loading">
        <el-table-column prop="afterSaleNo" :label="t('after_sale.after_sale_no')" width="200" />
        <el-table-column :label="t('after_sale.user')" width="110">
          <template #default="{ row }">{{ row.buyerName || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('order_manage.product')" min-width="200">
          <template #default="{ row }">
            <template v-if="displayItems(row).length">
              <div v-for="item in displayItems(row)" :key="item.id" class="order-item">
                <el-tag v-if="isMyItems(row)" size="small" type="info" class="mine-tag">
                  {{ t('after_sale.my_products') }}
                </el-tag>
                {{ itemName(row, item) }} x{{ item.quantity }}
              </div>
            </template>
            <span v-else class="no-data">-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('after_sale.type')" width="100">
          <template #default="{ row }">{{ typeText(row.type) }}</template>
        </el-table-column>
        <el-table-column :label="t('after_sale.reason')" min-width="150">
          <template #default="{ row }">{{ reasonText(row.reasonCode, row.reason) }}</template>
        </el-table-column>
        <el-table-column :label="t('after_sale.amount')" width="120">
          <template #default="{ row }">
            <span class="amount">¥{{ row.refundAmount }}</span>
            <div v-if="row.orderPayAmount" class="sub-text">{{ t('after_sale.pay_amount') }} ¥{{ row.orderPayAmount }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('after_sale.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="applyTime" :label="t('after_sale.apply_time')" :formatter="dateTimeFormatter" width="165" />
        <!-- 操作列：按状态归一渲染，不留空白（与订单管理页同一约定） -->
        <el-table-column :label="t('order.actions')" width="240" fixed="right">
          <template #default="{ row }">
            <template v-if="Number(row.status) === 0">
              <el-button type="primary" size="small" :disabled="isMixed(row)" @click="agree(row)">{{ t('after_sale.agree') }}</el-button>
              <el-button type="danger" size="small" :disabled="isMixed(row)" @click="openReject(row)">{{ t('after_sale.reject') }}</el-button>
              <div v-if="isMixed(row)" class="sub-text">{{ t('after_sale.mixed_notice') }}</div>
            </template>
            <template v-else-if="Number(row.status) === 1">
              <el-button type="success" size="small" :disabled="isMixed(row)" @click="finish(row)">{{ t('after_sale.finish') }}</el-button>
              <div v-if="isMixed(row)" class="sub-text">{{ t('after_sale.mixed_notice') }}</div>
            </template>
            <span v-else class="no-data">{{ finishedHint(row) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" :description="t('after_sale.no_data')" />

      <el-pagination
        v-if="total > 0"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        layout="prev, pager, next"
        class="pagination"
        @current-change="handlePageChange"
      />
    </el-card>

    <el-dialog v-model="rejectVisible" :title="t('after_sale.reject_title')" width="460px">
      <el-form label-width="90px">
        <el-form-item :label="t('after_sale.after_sale_no')">
          <span>{{ currentRow.afterSaleNo }}</span>
        </el-form-item>
        <el-form-item :label="t('after_sale.reject_reason')" required>
          <el-input
            v-model="rejectRemark"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            :placeholder="t('after_sale.reject_reason_placeholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="danger" :loading="handling" @click="submitReject">{{ t('after_sale.confirm_reject') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getManageAfterSaleList, handleAfterSale } from '@/api/afterSale'
import { isAdmin } from '@/utils/auth'
import { dateTimeFormatter } from '@/utils/datetime'
import { useAfterSaleReasons } from '@/composables/useAfterSaleReasons'

// 【R20】全页 i18n：表头、筛选器、弹窗、Toast 与 console 提示统一走语言包。
// 售后域文案全部复用既有 after_sale.*（含 demo_notice / status_* / type_* / agree / reject / finish）；
// 商品列与操作列复用 order_manage.product、order.actions（与订单管理页同一约定）。
const { t } = useI18n()
// 【2026-10-10】原因按 reasonCode 走本地化字典；row.reason 是申请时写库的中文快照，仅作兜底
const { text: reasonText } = useAfterSaleReasons()

const list = ref([])
const loading = ref(false)
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const statusFilter = ref(-1)

const rejectVisible = ref(false)
const rejectRemark = ref('')
const handling = ref(false)
const currentRow = ref({})

const typeText = (type) => {
  const map = {
    1: t('after_sale.type_refund_only'),
    2: t('after_sale.type_return_refund'),
    3: t('after_sale.type_exchange')
  }
  return map[Number(type)] || t('after_sale.status_unknown')
}

const statusText = (status) => {
  const map = {
    0: t('after_sale.status_pending'),
    1: t('after_sale.status_agreed'),
    2: t('after_sale.status_finished'),
    3: t('after_sale.status_rejected'),
    4: t('after_sale.status_cancelled')
  }
  return map[Number(status)] || t('after_sale.status_unknown')
}

const statusType = (status) => {
  const map = { 0: 'warning', 1: 'primary', 2: 'success', 3: 'danger', 4: 'info' }
  return map[Number(status)] || 'info'
}

const finishedHint = (row) => {
  const s = Number(row.status)
  if (s === 2) return t('after_sale.status_finished')
  if (s === 3) return t('after_sale.status_rejected')
  if (s === 4) return t('after_sale.cancelled_by_buyer')
  return '-'
}

// 【P1-6】商品列：优先展示本商家明细（myItems），无则回退整单明细（items）。
// 两者字段不同：myItems 是 after_sale_item（无商品名），items 是 order_item（有商品名）。
const isMyItems = (row) => !!(row.myItems && row.myItems.length)
const displayItems = (row) => (isMyItems(row) ? row.myItems : (row.items || []))
const itemName = (row, item) => {
  if (item.productName) return item.productName
  const oi = (row.items || []).find((i) => i.id === item.orderItemId)
  return oi ? oi.productName : `#${item.productId}`
}

// 【P1-6】混合归属：按商品售后（item_scope=1）且本商家明细少于全部明细 ⇒
// 该售后单含别家商品，本商家无权处理（与后端 handle 的 403 口径一致）。
// 前端置灰只是体验，后端校验是唯一权威。
//
// ⚠️【管理员豁免，2026-09-28 补】路由 meta.roles 含 admin，管理员也会打开本页；
// 而后端 handle 对 ROLE_ADMIN **不做**明细级归属校验、且 admin 会话的 merchantId 为空
// ⇒ 接口不会回填 myItems ⇒ 若仍按「myItems < saleItems」判定，会把管理员**误判为
// 混合归属而全部置灰**（审核发现的缺陷）。这里必须与后端口径一致：管理员一律不置灰。
const isMixed = (row) =>
  !isAdmin() &&
  Number(row.itemScope) === 1 &&
  (row.myItems || []).length < (row.saleItems || []).length

const loadList = async (page = 1) => {
  loading.value = true
  try {
    const params = { page, size: pageSize.value }
    if (statusFilter.value >= 0) {
      params.status = statusFilter.value
    }
    const res = await getManageAfterSaleList(params)
    list.value = res.data?.records || []
    total.value = res.data?.total || 0
    currentPage.value = page
  } catch (e) {
    console.error(t('after_sale.load_failed'), e)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page) => loadList(page)

const agree = async (row) => {
  const tip = Number(row.type) === 1
    ? t('after_sale.confirm_agree_refund_only')
    : t('after_sale.confirm_agree_return')
  try {
    await ElMessageBox.confirm(tip, t('common.warning'), { type: 'warning' })
  } catch (e) {
    return
  }
  handling.value = true
  try {
    await handleAfterSale(row.id, { action: 'agree' })
    ElMessage.success(t('after_sale.handle_success'))
    await loadList(currentPage.value)
  } catch (e) {
    console.error(t('after_sale.handle_failed'), e)
  } finally {
    handling.value = false
  }
}

const openReject = (row) => {
  currentRow.value = row
  rejectRemark.value = ''
  rejectVisible.value = true
}

const submitReject = async () => {
  if (!rejectRemark.value.trim()) {
    ElMessage.warning(t('after_sale.reject_reason_required'))
    return
  }
  handling.value = true
  try {
    await handleAfterSale(currentRow.value.id, { action: 'reject', remark: rejectRemark.value.trim() })
    ElMessage.success(t('after_sale.reject_success'))
    rejectVisible.value = false
    await loadList(currentPage.value)
  } catch (e) {
    console.error(t('after_sale.reject_failed'), e)
  } finally {
    handling.value = false
  }
}

const finish = async (row) => {
  const tip = Number(row.type) === 2
    ? t('after_sale.confirm_finish_return')
    : t('after_sale.confirm_finish')
  try {
    await ElMessageBox.confirm(tip, t('common.warning'), { type: 'warning' })
  } catch (e) {
    return
  }
  handling.value = true
  try {
    await handleAfterSale(row.id, { action: 'finish' })
    ElMessage.success(t('after_sale.finish_success'))
    await loadList(currentPage.value)
  } catch (e) {
    console.error(t('after_sale.finish_failed'), e)
  } finally {
    handling.value = false
  }
}

onMounted(() => loadList(1))
</script>

<style scoped>
.after-sale-manage {
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
  gap: 10px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.order-item {
  font-size: 13px;
  padding: 2px 0;
}

.mine-tag {
  margin-right: 6px;
}

.amount {
  color: #f56c6c;
  font-weight: bold;
}

.sub-text {
  font-size: 12px;
  color: #909399;
}

.no-data {
  color: #c0c4cc;
  font-size: 13px;
}

.pagination {
  margin-top: 16px;
  text-align: right;
}
</style>
