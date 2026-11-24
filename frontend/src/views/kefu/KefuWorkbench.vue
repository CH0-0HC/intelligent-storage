<template>
  <div class="kefu-workbench">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('service_ticket.title') }}</span>
          <el-button type="primary" size="small" @click="loadAll">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>

      <!-- 统计卡片：三个数量各用一次分页查询的 total（size=1 只要总数，不拉数据） -->
      <div class="stat-row">
        <div class="stat-card" :class="{ active: statusFilter === -1 }" @click="filterByStatus(-1)">
          <div class="stat-value">{{ stat.all }}</div>
          <div class="stat-label">{{ t('service_ticket.stat_all') }}</div>
        </div>
        <div class="stat-card" :class="{ active: statusFilter === 0 }" @click="filterByStatus(0)">
          <div class="stat-value warning">{{ stat.pending }}</div>
          <div class="stat-label">{{ t('service_ticket.status_pending') }}</div>
        </div>
        <div class="stat-card" :class="{ active: statusFilter === 1 }" @click="filterByStatus(1)">
          <div class="stat-value success">{{ stat.resolved }}</div>
          <div class="stat-label">{{ t('service_ticket.status_resolved') }}</div>
        </div>
      </div>

      <!-- 裁定标准：默认收起，但客服裁定前必须能看到同一段原文。
           刻意放在列表上方而不是裁定页，是为了让「批量判断」时口径先入眼。 -->
      <el-collapse v-model="standardOpen" class="standard-collapse">
        <el-collapse-item :title="t('service_ticket.cs_standard')" name="standard">
          <div class="standard-text">{{ meta.csStandard }}</div>
          <div class="standard-tip">{{ t('service_ticket.cs_standard_tip') }}</div>
        </el-collapse-item>
      </el-collapse>

      <!-- 筛选区 -->
      <div class="filter-bar">
        <el-select v-model="statusFilter" size="small" style="width: 150px">
          <el-option :label="t('service_ticket.all')" :value="-1" />
          <el-option :label="t('service_ticket.status_pending')" :value="0" />
          <el-option :label="t('service_ticket.status_resolved')" :value="1" />
          <el-option :label="t('service_ticket.status_cancelled')" :value="2" />
        </el-select>
        <el-input
          v-model="keyword"
          size="small"
          style="width: 240px"
          clearable
          :placeholder="t('service_ticket.keyword_placeholder')"
          @keyup.enter="search"
        />
        <el-button type="primary" size="small" @click="search">{{ t('common.search') }}</el-button>
        <el-button size="small" @click="reset">{{ t('common.reset') }}</el-button>
      </div>

      <el-table :data="list" stripe v-loading="loading">
        <el-table-column prop="ticketNo" :label="t('service_ticket.ticket_no')" width="190" />
        <el-table-column prop="orderNo" :label="t('service_ticket.order_no')" width="180" />
        <el-table-column :label="t('service_ticket.buyer')" width="110">
          <template #default="{ row }">{{ row.buyerName || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.product')" min-width="160">
          <template #default="{ row }">{{ row.productName || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.reason')" min-width="130">
          <template #default="{ row }">{{ reasonText(row.reasonCode) }}</template>
        </el-table-column>
        <!-- 超期天数是裁定的核心事实，单独成列并标红 -->
        <el-table-column :label="t('service_ticket.days_overdue')" width="100">
          <template #default="{ row }">
            <span class="overdue">{{ Number(row.daysOverdue) || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.return_window')" width="150">
          <template #default="{ row }">{{ windowText(row.returnWindowDays) }}</template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.status')" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row)" size="small">{{ statusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('service_ticket.resolution')" width="150">
          <template #default="{ row }">{{ resolutionText(row) }}</template>
        </el-table-column>
        <el-table-column prop="createTime" :label="t('service_ticket.create_time')" :formatter="dateTimeFormatter" width="170" />
        <el-table-column :label="t('service_ticket.resolve_title')" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              size="small"
              :disabled="Number(row.status) !== 0"
              @click="goResolve(row)"
            >
              {{ t('service_ticket.resolve_submit') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" :description="t('service_ticket.title')" />

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
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getManageServiceTickets, getServiceTicketMeta } from '@/api/serviceTicket'
import { dateTimeFormatter } from '@/utils/datetime'

const router = useRouter()
const { t, locale } = useI18n()

const loading = ref(false)
const list = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)

// -1 表示「全部」：后端 status 只在 >= 0 时才拼进查询条件，这里不传即可
const statusFilter = ref(-1)
const keyword = ref('')

const meta = ref({})
const reasons = ref({})
const standardOpen = ref([])

// 三个统计数字。裁定页返回时会重新拉取，保证卡片与列表一致。
const stat = ref({ all: 0, pending: 0, resolved: 0 })

/**
 * 取某状态下的工单总数。
 * 【为什么用 size=1】只需要 total 字段，传 size=1 让后端少查一页数据；
 * 后端 PageBounds 会把 size 归一到合法区间，不会因为 1 而报错。
 */
const fetchTotal = async (status) => {
  const params = { page: 1, size: 1 }
  if (status !== undefined) {
    params.status = status
  }
  const res = await getManageServiceTickets(params)
  return res.data?.total || 0
}

const loadStats = async () => {
  try {
    const [all, pending, resolved] = await Promise.all([
      fetchTotal(undefined),
      fetchTotal(0),
      fetchTotal(1)
    ])
    stat.value = { all, pending, resolved }
  } catch (e) {
    console.error('加载客服工单统计失败', e)
  }
}

const loadList = async (page = 1) => {
  loading.value = true
  try {
    const params = { page, size: pageSize.value }
    if (statusFilter.value !== -1) {
      params.status = statusFilter.value
    }
    if (keyword.value) {
      params.keyword = keyword.value
    }
    const res = await getManageServiceTickets(params)
    list.value = res.data?.records || []
    total.value = res.data?.total || 0
    currentPage.value = page
  } catch (e) {
    console.error('加载客服工单列表失败', e)
  } finally {
    loading.value = false
  }
}

const loadAll = () => {
  loadList(1)
  loadStats()
}

const search = () => loadList(1)

const reset = () => {
  statusFilter.value = -1
  keyword.value = ''
  loadList(1)
}

/** 点统计卡片即按该状态筛选，省掉「卡片看得到、列表还要再选一次」 */
const filterByStatus = (status) => {
  statusFilter.value = status
  loadList(1)
}

const handlePageChange = (page) => loadList(page)

const goResolve = (row) => router.push(`/kefu/ticket/${row.id}`)

const reasonText = (code) => {
  if (!code) {
    return '-'
  }
  // 禁止动态拼接 i18n 键名；文案一律来自后端 meta.reasons
  return reasons.value[code] || code
}

const windowText = (days) => {
  const n = Number(days)
  if (!Number.isFinite(n)) {
    return '-'
  }
  // return_window_text 是标签（不含 {days} 占位符），天数补在标签之后。
  // 0 表示商家把该商品设为「不支持退货退款」——客服裁定第 3 条正好针对这种商品。
  return `${t('service_ticket.return_window_text')} ${n > 0 ? n : 0}`
}

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
    return t('service_ticket.status_resolved')
  }
  return t('service_ticket.status_cancelled')
}

const resolutionText = (row) => {
  if (Number(row.status) === 0) {
    return '-'
  }
  if (Number(row.resolution) === 1) {
    return t('service_ticket.resolution_allow')
  }
  if (Number(row.resolution) === 2) {
    return t('service_ticket.resolution_reject')
  }
  return '-'
}

// 【2026-10-10】裁定标准原文与原因字典由后端按 lang 下发，抽出来便于切语言时重拉。
const loadMeta = async () => {
  try {
    const res = await getServiceTicketMeta()
    meta.value = res.data || {}
    reasons.value = meta.value.reasons || {}
  } catch (e) {
    console.error('加载客服裁定标准失败', e)
  }
}

onMounted(async () => {
  await loadMeta()
  loadAll()
})

watch(locale, () => { loadMeta() })
</script>

<style scoped>
.kefu-workbench {
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

.stat-row {
  display: flex;
  gap: 14px;
  margin-bottom: 16px;
}

.stat-card {
  flex: 1;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 14px 18px;
  background: var(--bg-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.stat-card.active {
  border-color: var(--el-color-primary);
  background: var(--bg-card);
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.stat-value.warning {
  color: #e6a23c;
}

.stat-value.success {
  color: #67c23a;
}

.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-muted);
}

.standard-collapse {
  margin-bottom: 12px;
}

/* pre-line：csStandard 为多行原文，换行必须保留 */
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

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.overdue {
  color: #f56c6c;
  font-weight: bold;
}

.pagination {
  margin-top: 16px;
  text-align: right;
}
</style>
