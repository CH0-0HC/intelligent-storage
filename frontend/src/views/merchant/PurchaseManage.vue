<template>
  <div class="purchase-manage">
    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="page-title">{{ t('purchase.title') }}</span>
          <el-button type="primary" @click="showAddDialog">{{ t('purchase_manage.create_purchase') }}</el-button>
        </div>
      </template>

      <div class="search-bar">
        <el-input v-model="searchForm.orderNo" :placeholder="t('purchase_manage.order_no')" class="search-input" />
        <el-input v-model="searchForm.supplierName" :placeholder="t('purchase_manage.supplier_name')" class="search-input" />
        <el-select v-model="searchForm.status" :placeholder="t('purchase_manage.order_status')" class="search-select">
          <el-option :label="t('purchase_manage.all')" :value="-1" />
          <el-option :label="t('purchase.status_pending')" :value="0" />
          <el-option :label="t('purchase_manage.audit_approved')" :value="1" />
          <el-option :label="t('order.status_completed')" :value="2" />
          <el-option :label="t('purchase_manage.audit_rejected')" :value="3" />
          <el-option :label="t('purchase.status_canceled')" :value="4" />
        </el-select>
        <el-button type="primary" @click="loadOrders">{{ t('common.search') }}</el-button>
        <el-button @click="resetSearch">{{ t('common.reset') }}</el-button>
      </div>

      <el-table :data="purchaseOrders" stripe v-loading="loading">
        <el-table-column prop="orderNo" :label="t('purchase_manage.order_no')" width="250" />
        <el-table-column prop="supplierName" :label="t('purchase.supplier')" width="250" />
        <el-table-column prop="totalAmount" :label="t('purchase_manage.total_amount')" :formatter="moneyFormatter" width="150" />
        <el-table-column prop="status" :label="t('purchase.status')" width="150">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">{{ getStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" :label="t('purchase_manage.audit_remark')" width="300" />
        <el-table-column prop="createTime" :label="t('order.create_time')" :formatter="dateTimeFormatter" width="180" />
        <el-table-column :label="t('order.actions')" width="380">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="viewOrder(row)">{{ t('purchase_manage.view') }}</el-button>
            <template v-if="row.status === 0">
              <el-button type="success" size="small" @click="auditOrder(row, 1)">{{ t('purchase_manage.audit_approved') }}</el-button>
              <el-button type="danger" size="small" @click="auditOrder(row, 2)">{{ t('purchase_manage.audit_rejected') }}</el-button>
              <el-button type="info" size="small" @click="cancelOrder(row)">{{ t('order.cancel') }}</el-button>
            </template>
            <el-button v-if="row.status === 1" type="warning" size="small" @click="completeOrder(row)">{{ t('purchase_manage.confirm_complete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
        :current-page="pagination.pageNum"
        :page-sizes="[10, 20, 50, 100]"
        :page-size="pagination.pageSize"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next, jumper"
      />
    </el-card>

    <el-dialog :title="t('purchase_manage.create_purchase')" v-model="addDialogVisible">
      <el-form :model="formData" label-width="100px">
        <el-form-item :label="t('purchase.supplier')">
          <el-input v-model="formData.supplierName" :placeholder="t('purchase_manage.supplier_name_placeholder')" />
        </el-form-item>
        <el-form-item :label="t('purchase_manage.purchase_amount')">
          <el-input v-model.number="formData.totalAmount" :placeholder="t('purchase_manage.purchase_amount_placeholder')" />
        </el-form-item>
        <el-form-item :label="t('purchase_manage.purchase_remark')">
          <el-input v-model="formData.remark" type="textarea" :placeholder="t('purchase_manage.purchase_remark_placeholder')" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitOrder">{{ t('common.submit') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog :title="t('purchase_manage.audit_title')" v-model="auditDialogVisible">
      <!-- 【测试报告#8】审核备注原先没有任何校验：「确认审核」不填任何内容直接
           「操作成功」，库里的 audit_remark 只能靠后端兜底默认值。这里改为必填，
           强制审核人写清依据，也避免「一键默认通过」。 -->
      <el-form ref="auditFormRef" :model="auditForm" :rules="auditRules" label-width="80px">
        <el-form-item :label="t('purchase_manage.audit_opinion')">
          <el-radio-group v-model="auditForm.auditResult">
            <el-radio :value="1">{{ t('purchase_manage.audit_pass') }}</el-radio>
            <el-radio :value="2">{{ t('purchase_manage.audit_reject') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('purchase_manage.audit_remark')" prop="remark">
          <el-input v-model="auditForm.remark" type="textarea" :placeholder="t('purchase_manage.audit_remark_placeholder')" :rows="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="confirmAudit">{{ t('purchase_manage.confirm_audit') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog :title="t('purchase_manage.detail_title')" v-model="viewDialogVisible">
      <div v-if="currentOrder" class="order-detail">
        <div class="detail-row">
          <span class="detail-label">{{ t('purchase_manage.order_no') }}:</span>
          <span class="detail-value">{{ currentOrder.orderNo }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ t('purchase.supplier') }}:</span>
          <span class="detail-value">{{ currentOrder.supplierName }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ t('purchase_manage.purchase_amount') }}:</span>
          <span class="detail-value">{{ formatMoney(currentOrder.totalAmount, { currency: true }) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ t('purchase_manage.order_status') }}:</span>
          <el-tag :type="getStatusType(currentOrder.status)">{{ getStatusText(currentOrder.status) }}</el-tag>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ t('purchase_manage.audit_remark') }}:</span>
          <span class="detail-value">{{ currentOrder.auditRemark || t('purchase_manage.none') }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ t('purchase_manage.purchase_remark') }}:</span>
          <span class="detail-value">{{ currentOrder.remark || t('purchase_manage.none') }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ t('order.create_time') }}:</span>
          <span class="detail-value">{{ formatDateTime(currentOrder.createTime) }}</span>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPurchaseOrderList,
  addPurchaseOrder,
  auditPurchaseOrder,
  completePurchaseOrder,
  cancelPurchaseOrder,
  getPurchaseOrderById
} from '@/api/purchase'
import { PURCHASE_STATUS_MAP } from '@/utils/orderStatus'
import { formatDateTime, dateTimeFormatter } from '@/utils/datetime'
import { formatMoney, moneyFormatter } from '@/utils/money'

// 【R20】全页 i18n：页标题复用 purchase.title，表头/表单标签复用 purchase.supplier /
// purchase.status / order.create_time / order.actions / common.*；
// 采购单状态「待审核」「已取消」复用 purchase.status_pending / purchase.status_canceled，
// 「已完成」「未知」复用 order.status_completed / order.unknown；
// 「取消订单」复用 order.cancel；本页独有文案登记在 purchase_manage.*。
const { t } = useI18n()

const purchaseOrders = ref([])
const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})
const searchForm = reactive({
  orderNo: '',
  supplierName: '',
  status: -1
})
const loading = ref(false)
const addDialogVisible = ref(false)
const auditDialogVisible = ref(false)
const viewDialogVisible = ref(false)
const currentOrder = ref(null)
const auditOrderData = ref(null)
const formData = reactive({ supplierName: '', totalAmount: 0, remark: '' })
const auditForm = reactive({ auditResult: 1, remark: '' })

// 【测试报告#8】审核备注必填 —— 表单原先没有任何 rules，「确认审核」不填内容也能成功。
// 用 validator 而不是静态 message：validator 在触发时才调 t()，切换语言后提示跟着变。
const auditFormRef = ref(null)
const auditRules = {
  remark: [
    {
      validator: (rule, value, callback) => {
        if (String(value ?? '').trim()) {
          callback()
        } else {
          callback(new Error(t('purchase_manage.audit_remark_required')))
        }
      },
      trigger: 'blur'
    }
  ]
}

// 采购单状态 → i18n key / 颜色 统一由 @/utils/orderStatus 提供（见该文件说明）。
// 状态 4「已取消」与订单列表、订单管理统一为 info。
const getStatusText = (status) => {
  // 存 i18n 键而非文案，保证切换语言时标签同步刷新
  return t(PURCHASE_STATUS_MAP[status]?.text || 'order.unknown')
}

const getStatusType = (status) => {
  return PURCHASE_STATUS_MAP[status]?.type || 'info'
}

const loadOrders = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    }
    if (searchForm.orderNo) params.orderNo = searchForm.orderNo
    if (searchForm.supplierName) params.supplierName = searchForm.supplierName
    if (searchForm.status >= 0) params.status = searchForm.status

    const response = await getPurchaseOrderList(params)
    if (response.data) {
      purchaseOrders.value = response.data.records || []
      pagination.total = response.data.total || 0
    }
  } catch (error) {
    console.error(t('purchase_manage.load_orders_failed'), error)
    ElMessage.error(t('purchase_manage.load_failed'))
  } finally {
    loading.value = false
  }
}

const showAddDialog = () => {
  formData.supplierName = ''
  formData.totalAmount = 0
  formData.remark = ''
  addDialogVisible.value = true
}

const submitOrder = async () => {
  if (!formData.supplierName.trim()) {
    ElMessage.warning(t('purchase_manage.supplier_name_required'))
    return
  }
  if (formData.totalAmount <= 0) {
    ElMessage.warning(t('purchase_manage.purchase_amount_invalid'))
    return
  }

  try {
    const response = await addPurchaseOrder({
      supplierName: formData.supplierName,
      totalAmount: formData.totalAmount,
      remark: formData.remark
    })
    ElMessage.success(response.message)
    addDialogVisible.value = false
    loadOrders()
  } catch (error) {
    console.error(t('purchase_manage.create_failed_log'), error)
    ElMessage.error(t('purchase_manage.create_failed'))
  }
}

const viewOrder = async (row) => {
  try {
    const response = await getPurchaseOrderById(row.id)
    currentOrder.value = response.data
  } catch (error) {
    currentOrder.value = row
    console.error(t('purchase_manage.load_detail_failed'), error)
  }
  viewDialogVisible.value = true
}

const auditOrder = (row, result) => {
  auditOrderData.value = row
  auditForm.auditResult = result
  auditForm.remark = ''
  auditDialogVisible.value = true
}

const confirmAudit = async () => {
  if (!auditOrderData.value) return

  // 【测试报告#8】先走 el-form 校验：空备注到此为止，不再直接「操作成功」。
  // 校验失败时字段下方已有红字，这里不重复 toast。
  try {
    await auditFormRef.value?.validate()
  } catch {
    return
  }

  try {
    const response = await auditPurchaseOrder(
      auditOrderData.value.id,
      auditForm.auditResult === 1 ? 1 : 3,
      auditForm.remark
    )
    ElMessage.success(response.message)
    auditDialogVisible.value = false
    loadOrders()
  } catch (error) {
    console.error(t('purchase_manage.audit_failed_log'), error)
    ElMessage.error(t('purchase_manage.audit_failed'))
  }
}

const completeOrder = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('purchase_manage.complete_confirm', { orderNo: row.orderNo }),
      t('common.warning'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
  } catch {
    return
  }

  try {
    const response = await completePurchaseOrder(row.id)
    ElMessage.success(response.message)
    loadOrders()
  } catch (error) {
    console.error(t('purchase_manage.complete_failed_log'), error)
    ElMessage.error(t('purchase_manage.complete_failed'))
  }
}

const cancelOrder = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('purchase_manage.cancel_confirm', { orderNo: row.orderNo }),
      t('common.warning'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
  } catch {
    return
  }

  try {
    const response = await cancelPurchaseOrder(row.id)
    ElMessage.success(response.message)
    loadOrders()
  } catch (error) {
    console.error(t('purchase_manage.cancel_failed_log'), error)
    ElMessage.error(t('purchase_manage.cancel_failed'))
  }
}

const handleSizeChange = (val) => {
  pagination.pageSize = val
  loadOrders()
}

const handleCurrentChange = (val) => {
  pagination.pageNum = val
  loadOrders()
}

const resetSearch = () => {
  searchForm.orderNo = ''
  searchForm.supplierName = ''
  searchForm.status = -1
  pagination.pageNum = 1
  loadOrders()
}

onMounted(() => {
  loadOrders()
})
</script>

<style scoped>
.purchase-manage { padding: 20px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.page-title { font-size: 18px; font-weight: 600; }

.search-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  align-items: center;
}

.search-input {
  width: 250px;
}

.search-select {
  width: 150px;
}

.order-detail { padding: 10px; }
.detail-row {
  display: flex;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}
.detail-row:last-child { border-bottom: none; }
.detail-label {
  width: 100px;
  font-weight: bold;
  color: #666;
}
.detail-value { flex: 1; }
</style>