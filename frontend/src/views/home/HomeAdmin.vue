<template>
  <div class="role-home admin-home">
    <!-- 角色标识条 -->
    <div class="role-banner admin-banner">
      <div class="role-banner-left">
        <el-icon class="role-banner-icon"><Setting /></el-icon>
        <div>
          <div class="role-banner-title">{{ t('home.role_home_page.admin_banner_title') }}</div>
          <div class="role-banner-desc">{{ t('home.role_home_page.admin_banner_desc') }}</div>
        </div>
      </div>
      <el-tag type="danger" effect="dark" round>{{ t('home.role_home_page.admin_role') }}</el-tag>
    </div>

    <!-- 平台总览 -->
    <div class="stats-section">
      <div class="section-header">
        <h3>{{ t('home.role_home_page.admin_overview') }}</h3>
        <el-button type="primary" text @click="loadDashboard" :loading="loading">
          <el-icon><Refresh /></el-icon> {{ t('home.role_home_page.refresh') }}
        </el-button>
      </div>
      <el-skeleton v-if="loading" :rows="2" animated />
      <div v-else class="stats-grid">
        <div
          v-for="(stat, index) in adminStats"
          :key="stat.title"
          class="stat-card"
          :style="{ '--card-delay': `${index * 0.08}s`, '--card-color': stat.color }"
          @click="stat.link && router.push(stat.link)"
          :class="{ 'card-clickable': stat.link }"
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

    <!-- 管理入口 -->
    <el-card class="section-card" shadow="hover">
      <template #header>
        <div class="section-header">
          <h3 class="section-title"><el-icon><Grid /></el-icon> {{ t('home.role_home_page.admin_entries') }}</h3>
        </div>
      </template>
      <div class="quick-grid">
        <div
          v-for="entry in adminEntries"
          :key="entry.path"
          class="quick-entry"
          @click="router.push(entry.path)"
        >
          <el-icon :size="26" :color="entry.color"><component :is="entry.icon" /></el-icon>
          <div>
            <div class="quick-entry-title">{{ entry.title }}</div>
            <div class="quick-entry-desc">{{ entry.desc }}</div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Setting, Refresh, Grid, User, Document,
  Money, ShoppingCart, Warning, Box
} from '@element-plus/icons-vue'
import { getDashboardStats } from '@/api/ai'
import { getUserList } from '@/api/user'

const router = useRouter()
const { t } = useI18n()
const loading = ref(true)
const userTotal = ref(0)
const dashboard = ref({
  todaySales: 0, todayOrders: 0, monthSales: 0,
  warningCount: 0, totalProducts: 0
})

const adminStats = computed(() => [
  { title: t('home.role_home_page.admin_total_users'), value: `${userTotal.value}`, icon: User, color: '#f56c6c', footer: t('home.role_home_page.admin_total_users_footer'), link: '/admin/users' },
  { title: t('home.role_home_page.admin_total_products'), value: `${dashboard.value.totalProducts}`, icon: Box, color: '#409eff', footer: t('home.role_home_page.admin_total_products_footer'), link: '/products' },
  { title: t('home.role_home_page.admin_today_orders'), value: `${dashboard.value.todayOrders}`, icon: ShoppingCart, color: '#67c23a', footer: t('home.role_home_page.admin_today_orders_footer'), link: '/orders' },
  { title: t('home.role_home_page.admin_month_sales'), value: `¥${Number(dashboard.value.monthSales || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`, icon: Money, color: '#7232dd', footer: t('home.role_home_page.admin_month_sales_footer'), link: '/orders' },
  { title: t('home.role_home_page.admin_warning'), value: `${dashboard.value.warningCount}`, icon: Warning, color: '#e6a23c', footer: t('home.role_home_page.admin_warning_footer'), link: '/products' }
])

const adminEntries = computed(() => [
  { title: t('home.role_home_page.admin_entry_users'), desc: t('home.role_home_page.admin_entry_users_desc'), path: '/admin/users', icon: User, color: '#f56c6c' },
  { title: t('home.role_home_page.admin_entry_logs'), desc: t('home.role_home_page.admin_entry_logs_desc'), path: '/admin/logs', icon: Document, color: '#409eff' },
  { title: t('home.role_home_page.admin_entry_orders'), desc: t('home.role_home_page.admin_entry_orders_desc'), path: '/orders', icon: ShoppingCart, color: '#67c23a' },
  { title: t('home.role_home_page.admin_entry_products'), desc: t('home.role_home_page.admin_entry_products_desc'), path: '/products', icon: Box, color: '#e6a23c' }
])

const loadDashboard = async () => {
  loading.value = true
  try {
    const res = await getDashboardStats()
    const data = res?.data ?? res ?? {}
    dashboard.value = {
      todaySales: data.todaySales ?? 0,
      todayOrders: data.todayOrders ?? 0,
      monthSales: data.monthSales ?? 0,
      warningCount: data.warningCount ?? 0,
      totalProducts: data.totalProducts ?? 0
    }
  } catch (e) {
    console.error('加载平台总览失败', e)
    ElMessage.warning(t('home.role_home_page.admin_load_failed'))
  } finally {
    loading.value = false
  }

  try {
    const res = await getUserList()
    // getUserList 返回 Result<List<User>>，data 是数组。
    // 原实现 `Array.isArray(list) ? list.length : (list.total ?? 0)` 在
    // 后端改成返回分页对象时会拿到 undefined，这里补上兜底，
    // 避免「平台用户总数」显示成 undefined。
    const list = res?.data ?? res ?? []
    if (Array.isArray(list)) {
      userTotal.value = list.length
    } else {
      userTotal.value = list.total ?? list.records?.length ?? 0
    }
  } catch (e) {
    console.error('加载用户总数失败', e)
  }
}

onMounted(loadDashboard)
</script>

<style scoped>
.role-home { display: flex; flex-direction: column; gap: 20px; }

.role-banner {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 24px; border-radius: 16px;
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
  /* 本页整体位于 .main-content 的模糊层内，去掉嵌套 backdrop-filter，
     改用玻璃高光层保持通透观感（详见 glass.scss 的性能说明）。 */
  background-image: var(--glass-sheen-gradient);
}
.admin-banner {
  background: linear-gradient(135deg, rgba(245,108,108,0.16), rgba(245,108,108,0.03));
  border-left: 4px solid #f56c6c;
}
.role-banner-left { display: flex; align-items: center; gap: 14px; }
.role-banner-icon { font-size: 30px; color: #f56c6c; }
.role-banner-title { font-size: 18px; font-weight: 600; color: var(--glass-text-primary, #e8e8e8); }
.role-banner-desc { font-size: 13px; color: var(--glass-text-secondary, #a0a0a8); margin-top: 2px; }

.stats-section { display: flex; flex-direction: column; gap: 12px; }
.section-header { display: flex; align-items: center; justify-content: space-between; }
.section-header h3 { margin: 0; font-size: 17px; color: var(--glass-text-primary, #e8e8e8); }
.section-title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 16px; color: var(--glass-text-primary, #e8e8e8); }

.stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; }
.stat-card {
  position: relative; padding: 20px; border-radius: 16px;
  background: var(--glass-bg, rgba(255,255,255,0.06));
  border: 1px solid var(--glass-border, rgba(255,255,255,0.12));
  overflow: hidden; animation: cardIn .4s ease both; animation-delay: var(--card-delay, 0s);
}
.stat-card::before {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px;
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

.section-card { border-radius: 18px; background: var(--glass-bg, rgba(255,255,255,0.06)); border: 1px solid var(--glass-border, rgba(255,255,255,0.12)); }
.quick-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.quick-entry {
  display: flex; align-items: center; gap: 14px; padding: 14px 16px; border-radius: 12px;
  cursor: pointer; border: 1px solid var(--glass-border-light, rgba(255,255,255,0.08));
  transition: all .2s ease;
}
.quick-entry:hover { background: rgba(245,108,108,0.08); transform: translateX(3px); }
.quick-entry-title { font-size: 14px; font-weight: 600; color: var(--glass-text-primary, #e8e8e8); }
.quick-entry-desc { font-size: 12px; color: var(--glass-text-muted, #8a8a92); margin-top: 2px; }

@keyframes cardIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
