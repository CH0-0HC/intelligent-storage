<template>
  <div class="service-ticket-list-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('service_ticket.my_title') }}</span>
          <div class="header-right">
            <el-button size="small" @click="goOrders">{{ t('service_ticket.back_orders') }}</el-button>
            <el-button type="primary" link size="small" @click="goAfterSale">
              {{ t('service_ticket.my_after_sale') }}
            </el-button>
            <el-button type="primary" size="small" @click="loadList(currentPage)">
              {{ t('common.refresh') }}
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" stripe v-loading="loading">
        <el-table-column prop="ticketNo" :label="t('service_ticket.ticket_no')" width="190" />
        <el-table-column prop="orderNo" :label="t('service_ticket.order_no')" width="180" />
        <el-table-column :label="t('service_ticket.product')" min-width="170">
          <template #default="{ row }">{{ row.productName || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.reason')" min-width="130">
          <template #default="{ row }">{{ reasonText(row.reasonCode) }}</template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.days_overdue')" width="100">
          <template #default="{ row }">
            <span class="overdue">{{ Number(row.daysOverdue) || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.status')" width="200">
          <template #default="{ row }">
            <el-tag :type="statusType(row)" size="small">{{ statusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.cs_remark')" min-width="180">
          <template #default="{ row }">{{ row.csRemark || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" :label="t('service_ticket.create_time')" :formatter="dateTimeFormatter" width="170" />
        <!-- 操作列：查看始终可用；撤销仅「待客服裁定」（后端也只允许该状态） -->
        <el-table-column :label="t('common.detail')" width="150" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDetail(row)">
              {{ t('service_ticket.view') }}
            </el-button>
            <el-button
              v-if="Number(row.status) === 0"
              type="danger"
              link
              size="small"
              @click="cancelTicket(row)"
            >
              {{ t('service_ticket.cancel') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" :description="t('service_ticket.my_title')" />

      <el-pagination
        v-if="total > 0"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        layout="prev, pager, next, jumper"
        class="pagination"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 详情用弹窗展示：路由里只有 /service-ticket（列表），不新增详情路由 -->
    <el-dialog v-model="detailVisible" :title="t('service_ticket.my_title')" width="680px">
      <div v-loading="detailLoading" class="detail-body">
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.ticket_no') }}</span>
          <span class="value">{{ detail.ticketNo || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.order_no') }}</span>
          <span class="value">{{ detail.orderNo || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.product') }}</span>
          <span class="value">{{ detail.productName || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.reason') }}</span>
          <span class="value">{{ reasonText(detail.reasonCode) }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.return_window') }}</span>
          <span class="value">{{ windowText(detail.returnWindowDays) }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.days_overdue') }}</span>
          <span class="value overdue">{{ Number(detail.daysOverdue) || 0 }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.description') }}</span>
          <span class="value">{{ detail.description || '-' }}</span>
        </div>
        <div v-if="detailImages.length" class="detail-row">
          <span class="label">{{ t('service_ticket.images') }}</span>
          <span class="value">
            <el-image
              v-for="url in detailImages"
              :key="url"
              :src="getImageUrl(url)"
              :preview-src-list="detailImages.map((u) => getImageUrl(u))"
              :preview-teleported="true"
              fit="cover"
              class="detail-image"
            />
          </span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.status') }}</span>
          <span class="value">
            <el-tag :type="statusType(detail)" size="small">{{ statusText(detail) }}</el-tag>
          </span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.cs_remark') }}</span>
          <span class="value">{{ detail.csRemark || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.create_time') }}</span>
          <span class="value">{{ formatDateTime(detail.createTime) }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('service_ticket.operate_time') }}</span>
          <span class="value">{{ formatDateTime(detail.operateTime) }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">{{ t('common.cancel') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMyServiceTickets,
  getServiceTicketDetail,
  cancelServiceTicket,
  getServiceTicketMeta
} from '@/api/serviceTicket'
import { getImageUrl } from '@/utils/image'
import { formatDateTime, dateTimeFormatter } from '@/utils/datetime'

const router = useRouter()
const { t, locale } = useI18n()

const list = ref([])
const loading = ref(false)
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref({})

// 原因字典：后端 meta.reasons（code → 文案）。加载失败时退化成显示原始 code，
// 不影响列表主流程（工单号 / 状态 / 超期天数才是关键信息）。
const reasons = ref({})

const detailImages = computed(() => detail.value.images || [])

/**
 * 状态标签。
 *
 * 【为什么 status=1 还要看 resolution】后端刻意把「已裁定」做成单一状态
 * （可读性交给 resolution），所以标签文案与颜色必须由 resolution 决定，
 * 否则「客服已同意」和「客服已驳回」会长得一模一样。
 */
const statusType = (row) => {
  const status = Number(row.status)
  if (status === 0) {
    return 'warning'
  }
  if (status === 1) {
    return Number(row.resolution) === 2 ? 'danger' : 'success'
  }
  return 'info'
}

const statusText = (row) => {
  const status = Number(row.status)
  if (status === 0) {
    return t('service_ticket.status_pending')
  }
  if (status === 1) {
    return Number(row.resolution) === 2
      ? t('service_ticket.resolution_reject')
      : t('service_ticket.resolution_allow')
  }
  return t('service_ticket.status_cancelled')
}

const reasonText = (code) => {
  if (!code) {
    return '-'
  }
  // 键名不得动态拼接（i18n 门禁要求字面量键），字典一律来自后端下发的文案
  return reasons.value[code] || code
}

const windowText = (days) => {
  const n = Number(days)
  if (!Number.isFinite(n)) {
    return '-'
  }
  // 【口径】i18n 的 return_window_text 是一个**标签**（「退货期限 / Return Window」），
  // 不含 {days} 占位符，因此这里把天数补在标签之后：
  //   · n = 0  —— 商家把该商品设为「不支持退货退款」，这也是一个有效期限值；
  //   · n > 0  —— 该商品申请时快照的退货期限天数。
  return `${t('service_ticket.return_window_text')} ${n > 0 ? n : 0}`
}

const loadList = async (page = 1) => {
  loading.value = true
  try {
    const res = await getMyServiceTickets({ page, size: pageSize.value })
    list.value = res.data?.records || []
    total.value = res.data?.total || 0
    currentPage.value = page
  } catch (e) {
    console.error('加载客服工单列表失败', e)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page) => loadList(page)

const openDetail = async (row) => {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = { ...row }
  try {
    const res = await getServiceTicketDetail(row.id)
    detail.value = res.data || row
  } catch (e) {
    console.error('加载客服工单详情失败', e)
  } finally {
    detailLoading.value = false
  }
}

const cancelTicket = async (row) => {
  try {
    await ElMessageBox.confirm(t('service_ticket.cancel_confirm'), t('common.warning'), {
      type: 'warning'
    })
  } catch (e) {
    // 用户点了取消：静默返回
    return
  }
  try {
    await cancelServiceTicket(row.id)
    ElMessage.success(t('service_ticket.cancel_success'))
    await loadList(currentPage.value)
  } catch (e) {
    console.error('撤销客服工单失败', e)
  }
}

const goOrders = () => router.push('/orders')
const goAfterSale = () => router.push('/after-sale')

// 【2026-10-10】字典由后端按 lang 下发，抽成独立函数以便切语言时单独重拉
//（列表数据本身与语言无关，没必要一起重查）。
const loadMeta = async () => {
  try {
    const res = await getServiceTicketMeta()
    reasons.value = res.data?.reasons || {}
  } catch (e) {
    console.error('加载诉求原因字典失败', e)
  }
}

onMounted(async () => {
  await loadList(1)
  await loadMeta()
})

watch(locale, () => { loadMeta() })
</script>

<style scoped>
.service-ticket-list-page {
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

.overdue {
  color: #f56c6c;
  font-weight: bold;
}

.pagination {
  margin-top: 16px;
  text-align: right;
}

.detail-body {
  min-height: 80px;
}

.detail-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.detail-row .label {
  width: 90px;
  flex: none;
  color: var(--text-muted);
}

.detail-row .value {
  color: var(--text-primary);
  word-break: break-all;
}

.detail-image {
  width: 72px;
  height: 72px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  margin-right: 8px;
  cursor: pointer;
}
</style>
