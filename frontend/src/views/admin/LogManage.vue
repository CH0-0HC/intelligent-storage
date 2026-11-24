<template>
  <div class="log-manage">
    <el-card>
      <template #header>
        <span class="page-title">{{ t('log.page_title') }}</span>
      </template>
      
      <el-table :data="logs" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" :label="t('log.user')" width="120" />
        <el-table-column prop="operation" :label="t('log.operation')" width="150" />
        <el-table-column prop="method" :label="t('log.method')" min-width="200" />
        <el-table-column prop="ip" :label="t('log.ip')" width="150" />
        <el-table-column prop="userAgent" :label="t('log.user_agent')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" :label="t('log.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? t('log.success') : t('log.failed') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="executeTime" :label="t('log.execute_time')" width="100">
          <template #default="{ row }">
            {{ row.executeTime }}ms
          </template>
        </el-table-column>
        <el-table-column prop="createTime" :label="t('log.time')" :formatter="dateTimeFormatterSeconds" width="180" />
        <!-- 【P1-5】result / params / errorMsg 是长文本，表格里放不下，
             通过「详情」弹窗查看。这三项正是本次修复补上的审计要素。 -->
        <el-table-column :label="t('log.detail')" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">
              {{ t('log.detail') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
        />
      </div>
    </el-card>

    <!-- 【P1-5】日志详情弹窗：展示 params / result / errorMsg / userAgent -->
    <el-dialog v-model="detailVisible" :title="t('log.detail_title')" width="720px">
      <el-descriptions :column="1" border>
        <el-descriptions-item :label="t('log.user')">
          {{ detailRow?.username || t('log.no_data') }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('log.operation')">
          {{ detailRow?.operation }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('log.method')">
          {{ detailRow?.method }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('log.ip')">
          {{ detailRow?.ip }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('log.user_agent')">
          {{ detailRow?.userAgent || t('log.no_data') }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('log.execute_time')">
          {{ detailRow?.executeTime }}ms
        </el-descriptions-item>
        <el-descriptions-item :label="t('log.params')">
          <pre class="log-pre">{{ detailRow?.params || t('log.no_data') }}</pre>
        </el-descriptions-item>
        <el-descriptions-item :label="t('log.result')">
          <pre class="log-pre">{{ detailRow?.result || t('log.no_data') }}</pre>
        </el-descriptions-item>
        <el-descriptions-item v-if="detailRow?.errorMsg" :label="t('log.error_msg')">
          <pre class="log-pre">{{ detailRow.errorMsg }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { getLogList } from '@/api/log'
import { dateTimeFormatterSeconds } from '@/utils/datetime'

// 【R20】全页 i18n：表头、页标题与错误提示统一走语言包 log.*
// 复用了 log 模块既有的 user / time / ip 键，避免同义键重复登记。
const { t } = useI18n()

const logs = ref([])
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

// 【P1-5】详情弹窗状态
const detailVisible = ref(false)
const detailRow = ref(null)

const openDetail = (row) => {
  detailRow.value = row
  detailVisible.value = true
}

const loadLogs = async (page = 1) => {
  loading.value = true
  try {
    const res = await getLogList({ page, size: pageSize.value })
    if (res.code === 200 && res.data) {
      logs.value = res.data.records || []
      total.value = res.data.total || 0
    }
    currentPage.value = page
  } catch (error) {
    // 注意：错误提示也走 t()，否则切到 English 时这条 Toast/日志会突然变中文
    console.error(t('log.load_failed'), error)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page) => {
  loadLogs(page)
}

onMounted(() => {
  loadLogs()
})
</script>

<style scoped>
.log-manage {
  padding: 20px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}

/* 【P1-5】详情弹窗里的长文本（JSON）保持原始缩进、可滚动 */
.log-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
</style>
