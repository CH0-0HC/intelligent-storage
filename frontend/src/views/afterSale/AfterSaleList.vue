<template>
  <div class="after-sale-list-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('after_sale.mine') }}</span>
          <div class="header-actions">
            <!-- 【客服通道】售后超期后只能走客服申诉，这里给买家一个查看自己客服工单的入口，
                 省得他还得回订单页/申请页找按钮 -->
            <el-button size="small" @click="goServiceTicket">
              {{ t('service_ticket.my_entry') }}
            </el-button>
            <el-button type="primary" size="small" @click="loadList()">
              <el-icon>Refresh</el-icon>
              {{ t('common.refresh') }}
            </el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane :label="t('after_sale.all')" name="all" />
        <el-tab-pane :label="t('after_sale.status_pending')" name="0" />
        <el-tab-pane :label="t('after_sale.status_agreed')" name="1" />
        <el-tab-pane :label="t('after_sale.status_finished')" name="2" />
        <el-tab-pane :label="t('after_sale.status_rejected')" name="3" />
        <el-tab-pane :label="t('after_sale.status_cancelled')" name="4" />
      </el-tabs>

      <div v-if="loading" class="state-block">
        <el-icon class="is-loading" :size="26"><Loading /></el-icon>
        <p>{{ t('order.loading') }}</p>
      </div>

      <el-empty v-else-if="list.length === 0" :description="t('after_sale.no_data')" />

      <div v-else class="sale-list">
        <div v-for="row in list" :key="row.id" class="sale-card">
          <div class="sale-header">
            <div class="sale-no">
              {{ t('after_sale.after_sale_no') }}：{{ row.afterSaleNo }}
            </div>
            <el-tag :type="afterSaleStatusType(row.status)" size="small">
              {{ t(afterSaleStatusKey(row.status)) }}
            </el-tag>
          </div>

          <div class="sale-body">
            <div class="sale-line">
              <span class="label">{{ t('after_sale.order_no') }}</span>
              <span class="value">{{ row.orderNo || '-' }}</span>
              <span class="label">{{ t('after_sale.type') }}</span>
              <span class="value">{{ t(afterSaleTypeKey(row.type)) }}</span>
            </div>
            <div class="sale-line">
              <span class="label">{{ t('after_sale.reason') }}</span>
              <span class="value">{{ reasonText(row.reasonCode, row.reason) }}</span>
            </div>
            <div class="sale-line">
              <span class="label">{{ t('after_sale.apply_time') }}</span>
              <span class="value">{{ formatDateTime(row.applyTime) }}</span>
              <span class="label">{{ t('after_sale.amount') }}</span>
              <span class="amount">¥{{ row.refundAmount }}</span>
            </div>
            <div v-if="row.auditRemark" class="sale-line">
              <span class="label">{{ t('after_sale.audit_remark') }}</span>
              <span class="value">{{ row.auditRemark }}</span>
            </div>
          </div>

          <div class="sale-actions">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">
              {{ t('after_sale.view_detail') }}
            </el-button>
            <el-button
              v-if="Number(row.status) === 0"
              type="danger"
              link
              size="small"
              @click="cancelApply(row)"
            >
              {{ t('after_sale.cancel_apply') }}
            </el-button>
          </div>
        </div>
      </div>

      <el-pagination
        v-if="!loading && total > 0"
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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Refresh } from '@element-plus/icons-vue'
import { cancelAfterSale, getAfterSaleList } from '@/api/afterSale'
import { afterSaleStatusKey, afterSaleStatusType, afterSaleTypeKey } from '@/utils/afterSale'
import { useAfterSaleReasons } from '@/composables/useAfterSaleReasons'
import { formatDateTime } from '@/utils/datetime'

const router = useRouter()
const { t } = useI18n()
// 【2026-10-10】原因按 reasonCode 走本地化字典；row.reason 是申请时写库的中文快照，仅作兜底
const { text: reasonText } = useAfterSaleReasons()

const activeTab = ref('all')
const list = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const loadList = async (page = 1) => {
  loading.value = true
  try {
    const params = { page, size: pageSize.value }
    if (activeTab.value !== 'all') {
      params.status = Number(activeTab.value)
    }
    const res = await getAfterSaleList(params)
    list.value = res.data?.records || []
    total.value = res.data?.total || 0
    currentPage.value = page
  } catch (e) {
    console.error('加载售后列表失败', e)
  } finally {
    loading.value = false
  }
}

const handleTabChange = () => loadList(1)
const handlePageChange = (page) => loadList(page)

const goDetail = (id) => router.push(`/after-sale/${id}`)
// 买家侧客服工单列表（router/index.js 已有 /service-ticket 路由，由别人维护该页面）
const goServiceTicket = () => router.push('/service-ticket')

const cancelApply = async (row) => {
  try {
    await ElMessageBox.confirm(t('after_sale.cancel_confirm'), t('common.warning'), { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await cancelAfterSale(row.id)
    ElMessage.success(t('after_sale.cancel_success'))
    await loadList(currentPage.value)
  } catch (e) {
    console.error('撤销售后失败', e)
  }
}

onMounted(() => loadList(1))
</script>

<style scoped>
.after-sale-list-page {
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

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.state-block {
  text-align: center;
  padding: 50px 0;
  color: var(--text-muted);
}

.sale-list {
  margin-top: 16px;
}

.sale-card {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  margin-bottom: 14px;
  overflow: hidden;
  background: var(--bg-card);
}

.sale-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 18px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
}

.sale-no {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.sale-body {
  padding: 12px 18px;
}

.sale-line {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--text-secondary);
  padding: 4px 0;
}

.sale-line .label {
  color: var(--text-muted);
}

.sale-line .value {
  color: var(--text-primary);
  margin-right: 16px;
}

.sale-line .amount {
  color: #f56c6c;
  font-weight: bold;
}

.sale-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 10px 18px;
  border-top: 1px solid var(--border-color);
}

.pagination {
  margin-top: 16px;
  text-align: right;
}
</style>
