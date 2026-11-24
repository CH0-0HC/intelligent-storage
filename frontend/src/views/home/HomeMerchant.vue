<template>
  <div class="role-home merchant-home">
    <!-- 角色标识条：让商家一眼确认自己处于商家工作台 -->
    <div class="role-banner merchant-banner">
      <div class="role-banner-left">
        <el-icon class="role-banner-icon"><Shop /></el-icon>
        <div>
          <div class="role-banner-title">{{ t('home.role_home_page.merchant_banner_title') }}</div>
          <div class="role-banner-desc">{{ t('home.role_home_page.merchant_banner_desc') }}</div>
        </div>
      </div>
      <el-tag type="primary" effect="dark" round>{{ t('home.role_home_page.merchant_id') }}{{ merchantId ?? t('home.role_home_page.merchant_unbound') }}</el-tag>
    </div>

    <!-- 经营数据概览 -->
    <div class="stats-section">
      <div class="section-header">
        <h3>{{ t('home.role_home_page.merchant_overview') }}</h3>
        <el-button type="primary" text @click="loadDashboard" :loading="loading">
          <el-icon><Refresh /></el-icon> {{ t('home.role_home_page.refresh') }}
        </el-button>
      </div>

      <el-skeleton v-if="loading" :rows="2" animated />

      <div v-else class="stats-grid">
        <div
          v-for="(stat, index) in merchantStats"
          :key="stat.title"
          class="stat-card"
          :class="{ 'card-clickable': stat.link }"
          :style="{ '--card-delay': `${index * 0.08}s`, '--card-color': stat.color }"
          @click="stat.link && router.push(stat.link)"
        >
          <div class="stat-content">
            <el-icon :size="40" :color="stat.color" class="stat-icon">
              <component :is="stat.icon" />
            </el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stat.value }}</div>
              <div class="stat-title">{{ stat.title }}</div>
            </div>
          </div>
          <div class="stat-footer" v-if="stat.footer">
            <span class="stat-footer-text">{{ stat.footer }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 快捷入口 + 待办 -->
    <el-row :gutter="24">
      <el-col :xs="24" :md="14">
        <el-card class="section-card" shadow="hover">
          <template #header>
            <div class="section-header">
              <h3 class="section-title"><el-icon><Grid /></el-icon> {{ t('home.quick_actions') }}</h3>
            </div>
          </template>
          <div class="quick-grid">
            <div
              v-for="entry in quickEntries"
              :key="entry.path"
              class="quick-entry"
              @click="router.push(entry.path)"
            >
              <el-icon :size="26" :color="entry.color"><component :is="entry.icon" /></el-icon>
              <div class="quick-entry-text">
                <div class="quick-entry-title">{{ entry.title }}</div>
                <div class="quick-entry-desc">{{ entry.desc }}</div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card class="section-card" shadow="hover">
          <template #header>
            <div class="section-header">
              <h3 class="section-title"><el-icon><Bell /></el-icon> {{ t('home.role_home_page.merchant_todos') }}</h3>
            </div>
          </template>
          <div class="todo-list">
            <div
              v-for="todo in merchantTodos"
              :key="todo.title"
              class="todo-item"
              @click="router.push(todo.link)"
            >
              <el-tag :type="todo.tagType" size="small" effect="light">{{ todo.count }}</el-tag>
              <span class="todo-title">{{ todo.title }}</span>
              <el-icon class="todo-arrow"><ArrowRight /></el-icon>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Shop, Refresh, Grid, Bell, ArrowRight,
  Money, ShoppingCart, Warning, Box, PieChart, Cpu
} from '@element-plus/icons-vue'
import { getDashboardStats } from '@/api/ai'
import { getMerchantId } from '@/utils/auth'

const router = useRouter()
const merchantId = computed(() => getMerchantId())
const loading = ref(true)

// 由后端 /ai/dashboard 返回的真实经营数据填充
const dashboard = ref({
  todaySales: 0,
  todayOrders: 0,
  weekSales: 0,
  weekOrders: 0,
  monthSales: 0,
  warningCount: 0,
  totalProducts: 0
})

const { t } = useI18n()

const merchantStats = computed(() => [
  { title: t('home.role_home_page.merchant_today_sales'), value: `¥${formatAmount(dashboard.value.todaySales)}`, icon: Money, color: '#409eff', footer: t('home.role_home_page.merchant_today_sales_footer', { count: dashboard.value.todayOrders }), link: '/merchant/orders' },
  { title: t('home.role_home_page.merchant_week_sales'), value: `¥${formatAmount(dashboard.value.weekSales)}`, icon: PieChart, color: '#67c23a', footer: t('home.role_home_page.merchant_week_sales_footer', { count: dashboard.value.weekOrders }), link: '/merchant/echarts' },
  { title: t('home.role_home_page.merchant_month_sales'), value: `¥${formatAmount(dashboard.value.monthSales)}`, icon: Money, color: '#7232dd', footer: t('home.role_home_page.merchant_month_sales_footer'), link: '/merchant/echarts' },
  { title: t('home.role_home_page.merchant_warning'), value: `${dashboard.value.warningCount}`, icon: Warning, color: '#e6a23c', footer: t('home.role_home_page.merchant_warning_footer'), link: '/merchant/inventory' },
  { title: t('home.role_home_page.merchant_on_sale'), value: `${dashboard.value.totalProducts}`, icon: Box, color: '#909399', footer: t('home.role_home_page.merchant_on_sale_footer'), link: '/merchant/products' }
])

const quickEntries = computed(() => [
  { title: t('home.role_home_page.quick_product_manage'), desc: t('home.role_home_page.quick_product_manage_desc'), path: '/merchant/products', icon: Box, color: '#409eff' },
  { title: t('home.role_home_page.quick_inventory_manage'), desc: t('home.role_home_page.quick_inventory_manage_desc'), path: '/merchant/inventory', icon: Warning, color: '#e6a23c' },
  { title: t('home.role_home_page.quick_order_process'), desc: t('home.role_home_page.quick_order_process_desc'), path: '/merchant/orders', icon: ShoppingCart, color: '#67c23a' },
  { title: t('home.role_home_page.quick_purchase_manage'), desc: t('home.role_home_page.quick_purchase_manage_desc'), path: '/merchant/purchase', icon: ShoppingCart, color: '#7232dd' },
  { title: t('home.role_home_page.quick_ai_analysis'), desc: t('home.role_home_page.quick_ai_analysis_desc'), path: '/merchant/ai-analysis', icon: Cpu, color: '#f56c6c' },
  { title: t('home.role_home_page.quick_data_charts'), desc: t('home.role_home_page.quick_data_charts_desc'), path: '/merchant/echarts', icon: PieChart, color: '#409eff' }
])

const merchantTodos = computed(() => [
  { title: t('home.role_home_page.todo_pending_ship'), count: dashboard.value.todayOrders || 0, link: '/merchant/orders', tagType: 'warning' },
  { title: t('home.role_home_page.todo_warning_products'), count: dashboard.value.warningCount || 0, link: '/merchant/inventory', tagType: 'danger' },
  { title: t('home.role_home_page.todo_on_sale'), count: dashboard.value.totalProducts || 0, link: '/merchant/products', tagType: 'info' }
])

function formatAmount(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const loadDashboard = async () => {
  loading.value = true
  try {
    const res = await getDashboardStats()
    // 兼容两种返回：{code,data:{...}} 或直接对象
    const data = res?.data ?? res ?? {}
    dashboard.value = {
      todaySales: data.todaySales ?? 0,
      todayOrders: data.todayOrders ?? 0,
      weekSales: data.weekSales ?? 0,
      weekOrders: data.weekOrders ?? 0,
      monthSales: data.monthSales ?? 0,
      warningCount: data.warningCount ?? 0,
      totalProducts: data.totalProducts ?? 0
    }
  } catch (e) {
    console.error('加载经营概览失败', e)
    ElMessage.warning(t('home.role_home_page.merchant_load_failed'))
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)
</script>

<style scoped>
.role-home { display: flex; flex-direction: column; gap: 20px; }

.role-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-radius: 16px;
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
  /* 去掉嵌套 backdrop-filter（见 glass.scss 性能说明） */
  background-image: var(--glass-sheen-gradient);
}
.merchant-banner {
  background: linear-gradient(135deg, rgba(64,158,255,0.18), rgba(64,158,255,0.04));
  border-left: 4px solid #409eff;
}
.role-banner-left { display: flex; align-items: center; gap: 14px; }
.role-banner-icon { font-size: 30px; color: #409eff; }
.role-banner-title { font-size: 18px; font-weight: 600; color: var(--glass-text-primary, #e8e8e8); }
.role-banner-desc { font-size: 13px; color: var(--glass-text-secondary, #a0a0a8); margin-top: 2px; }

.stats-section { display: flex; flex-direction: column; gap: 12px; }
.section-header { display: flex; align-items: center; justify-content: space-between; }
.section-header h3 { margin: 0; font-size: 17px; color: var(--glass-text-primary, #e8e8e8); }
.section-title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 16px; color: var(--glass-text-primary, #e8e8e8); }

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}
.stat-card {
  position: relative;
  padding: 20px;
  border-radius: 16px;
  background: var(--glass-bg, rgba(255,255,255,0.06));
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
  overflow: hidden;
  animation: cardIn 0.4s ease both;
  animation-delay: var(--card-delay, 0s);
}
.stat-card::before {
  content: '';
  position: absolute; top: 0; left: 0; right: 0; height: 3px;
  background: var(--card-color, #409eff); opacity: .7;
}
.card-clickable { cursor: pointer; transition: transform .2s ease, box-shadow .2s ease; }
.card-clickable:hover { transform: translateY(-3px); box-shadow: 0 12px 28px rgba(0,0,0,.22); }
.stat-content { display: flex; align-items: center; gap: 16px; }
.stat-info { flex: 1; }
.stat-value { font-size: 26px; font-weight: 700; color: var(--glass-text-primary, #e8e8e8); line-height: 1.2; }
.stat-title { font-size: 14px; color: var(--glass-text-secondary, #a0a0a8); margin-top: 4px; }
.stat-footer { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--glass-border-light, rgba(255,255,255,0.08)); }
.stat-footer-text { font-size: 12px; color: var(--glass-text-muted, #8a8a92); }

.section-card {
  border-radius: 18px;
  background: var(--glass-bg, rgba(255,255,255,0.06));
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
}

.quick-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.quick-entry {
  display: flex; align-items: center; gap: 14px;
  padding: 14px 16px; border-radius: 12px; cursor: pointer;
  border: 1px solid var(--glass-border-light, rgba(255,255,255,0.08));
  transition: all .2s ease;
}
.quick-entry:hover { background: rgba(64,158,255,0.08); transform: translateX(3px); }
.quick-entry-title { font-size: 14px; font-weight: 600; color: var(--glass-text-primary, #e8e8e8); }
.quick-entry-desc { font-size: 12px; color: var(--glass-text-muted, #8a8a92); margin-top: 2px; }

.todo-list { display: flex; flex-direction: column; gap: 8px; }
.todo-item {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 14px; border-radius: 10px; cursor: pointer;
  transition: background .2s ease;
}
.todo-item:hover { background: rgba(64,158,255,0.08); }
.todo-title { flex: 1; font-size: 14px; color: var(--glass-text-primary, #e8e8e8); }
.todo-arrow { color: var(--glass-text-muted, #8a8a92); }

@keyframes cardIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
