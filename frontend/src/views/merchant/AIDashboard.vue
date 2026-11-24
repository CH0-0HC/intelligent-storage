<template>
  <div class="ai-dashboard">
    <!-- 顶部状态栏 -->
    <div class="ai-header-bar">
      <div class="header-left">
        <h2 class="page-title">
          <el-icon :size="24"><DataAnalysis /></el-icon>
          {{ t('ai.title') }}
        </h2>
        <span class="last-update" v-if="lastUpdateTime">
          {{ t('ai_dashboard.data_update') }}{{ lastUpdateTime }}
        </span>
      </div>
      <div class="header-right">
        <el-tag :type="aiStatus.connected ? 'success' : 'danger'" size="small">
          {{ aiStatus.connected ? t('ai_dashboard.status_connected') : t('ai_dashboard.status_disconnected') }}
        </el-tag>
        <el-button type="primary" size="small" @click="refreshAll" :loading="refreshing">
          <el-icon><Refresh /></el-icon> {{ t('common.refresh') }}
        </el-button>
        <el-button size="small" @click="exportReport">
          <el-icon><Download /></el-icon> {{ t('ai_dashboard.export_report') }}
        </el-button>
      </div>
    </div>

    <!-- 核心指标卡片 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6" v-for="stat in statsCards" :key="stat.key">
        <div class="stat-card" :class="stat.colorClass" @click="activeTab = stat.tabTarget">
          <div class="stat-icon-wrap">
            <el-icon :size="28"><component :is="stat.icon" /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label }}</div>
            <div class="stat-trend" v-if="stat.trend !== undefined" :class="stat.trend >= 0 ? 'up' : 'down'">
              {{ stat.trend >= 0 ? '↑' : '↓' }} {{ Math.abs(stat.trend) }}%
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 功能标签页 -->
    <el-card class="main-content">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <!-- 标签1: 库存预警分析 -->
        <el-tab-pane :label="t('ai_dashboard.tab_warning')" name="warning">
          <div class="tab-content" v-loading="warningLoading">
            <el-row :gutter="16">
              <el-col :span="14">
                <div class="chart-box">
                  <div class="chart-header">
                    <span class="chart-title">{{ t('ai_dashboard.chart_inventory_overview') }}</span>
                  </div>
                  <div ref="inventoryChartRef" style="height: 320px"></div>
                </div>
              </el-col>
              <el-col :span="10">
                <div class="warning-summary">
                  <div class="summary-header">
                    <span>{{ t('ai_dashboard.warning_summary') }}</span>
                    <el-tag type="danger" size="small">{{ t('ai_dashboard.warning_count', { count: warningResult?.warningInventories?.length || 0 }) }}</el-tag>
                  </div>
                  <div class="warning-list" v-if="warningResult?.replenishments?.length">
                    <div v-for="item in warningResult.replenishments.slice(0, 5)" :key="item.productId"
                         class="warning-item" :class="{ urgent: item.priority === '紧急' }">
                      <div class="wi-header">
                        <span class="wi-name">{{ item.productName }}</span>
                        <el-tag :type="item.priority === '紧急' ? 'danger' : item.priority === '重要' ? 'warning' : 'info'" size="small">
                          {{ item.priority }}
                        </el-tag>
                      </div>
                      <div class="wi-detail">
                        <span>{{ t('ai_dashboard.label_stock') }}<b>{{ item.currentStock }}</b></span>
                        <span>{{ t('ai_dashboard.label_avg_daily_sales') }}<b>{{ item.avgDailySales }}</b></span>
                        <span>{{ t('ai_dashboard.label_recommend_restock') }}<b class="highlight">{{ item.recommendedQuantity }}</b>{{ t('cart.item_unit') }}</span>
                      </div>
                    </div>
                  </div>
                  <el-empty v-else :description="t('ai_dashboard.no_inventory_warning')" :image-size="80" />
                </div>
              </el-col>
            </el-row>
            <div class="ai-advice-box" v-if="warningResult?.aiAdvice">
              <div class="advice-label">
                <el-icon color="#e6a23c"><Opportunity /></el-icon>
                {{ t('ai_dashboard.ai_restock_advice') }}
              </div>
              <p class="advice-text">{{ warningResult.aiAdvice }}</p>
            </div>
          </div>
        </el-tab-pane>

        <!-- 标签2: 销售趋势分析 -->
        <el-tab-pane :label="t('ai_dashboard.tab_trend')" name="trend">
          <div class="tab-content" v-loading="trendLoading">
            <div class="chart-actions">
              <el-radio-group v-model="trendDimension" size="small" @change="loadSalesTrend">
                <el-radio-button value="daily">{{ t('ai_dashboard.dim_daily') }}</el-radio-button>
                <el-radio-button value="weekly">{{ t('ai_dashboard.dim_weekly') }}</el-radio-button>
                <el-radio-button value="monthly">{{ t('ai_dashboard.dim_monthly') }}</el-radio-button>
              </el-radio-group>
              <el-radio-group v-model="trendChartType" size="small" style="margin-left: 12px">
                <el-radio-button value="line">{{ t('ai_dashboard.chart_line') }}</el-radio-button>
                <el-radio-button value="bar">{{ t('ai_dashboard.chart_bar') }}</el-radio-button>
              </el-radio-group>
            </div>
            <div ref="salesTrendChartRef" style="height: 380px"></div>
            <div class="trend-insight" v-if="trendInsight">
              <el-icon color="#409eff"><InfoFilled /></el-icon>
              <span>{{ trendInsight }}</span>
            </div>
          </div>
        </el-tab-pane>

        <!-- 标签3: 经营报告 -->
        <el-tab-pane :label="t('ai_dashboard.tab_report')" name="report">
          <div class="tab-content" v-loading="reportLoading">
            <div class="report-toolbar">
              <el-radio-group v-model="reportPeriod" @change="generateBusinessReport">
                <el-radio-button value="week">{{ t('ai_dashboard.period_week') }}</el-radio-button>
                <el-radio-button value="month">{{ t('ai_dashboard.period_month') }}</el-radio-button>
                <el-radio-button value="quarter">{{ t('ai_dashboard.period_quarter') }}</el-radio-button>
              </el-radio-group>
            </div>
            <div v-if="businessReport" class="report-body">
              <el-row :gutter="16" class="report-kpis">
                <el-col :span="6" v-for="kpi in reportKPIs" :key="kpi.key">
                  <div class="report-kpi-card">
                    <div class="kpi-value">{{ kpi.value }}</div>
                    <div class="kpi-label">{{ kpi.label }}</div>
                    <div class="kpi-change" v-if="kpi.change" :class="kpi.change > 0 ? 'positive' : 'negative'">
                      {{ kpi.change > 0 ? '+' : '' }}{{ kpi.change }}%
                    </div>
                  </div>
                </el-col>
              </el-row>
              <el-row :gutter="16">
                <el-col :span="12">
                  <div ref="reportCategoryChartRef" style="height: 280px"></div>
                </el-col>
                <el-col :span="12">
                  <div class="top-products">
                    <h4>{{ t('ai_dashboard.top_products_title') }}</h4>
                    <div class="top-product-item" v-for="(p, idx) in (businessReport.topProducts || []).slice(0, 10)" :key="p.id">
                      <span class="rank" :class="{ 'top3': idx < 3 }">{{ idx + 1 }}</span>
                      <span class="name">{{ p.name }}</span>
                      <span class="sales">{{ t('product.sales') }} {{ p.sales || 0 }}</span>
                    </div>
                    <el-empty v-if="!businessReport.topProducts?.length" :description="t('ai_dashboard.no_data')" :image-size="60" />
                  </div>
                </el-col>
              </el-row>
              <div class="ai-advice-box" v-if="businessReport.aiAdvice">
                <div class="advice-label">
                  <el-icon color="#67c23a"><Opportunity /></el-icon>
                  {{ t('ai_dashboard.ai_business_advice') }}
                </div>
                <p class="advice-text">{{ businessReport.aiAdvice }}</p>
              </div>
            </div>
            <el-empty v-else :description="t('ai_dashboard.report_empty_hint')" :image-size="100" />
          </div>
        </el-tab-pane>

        <!-- 标签4: 商品推荐 -->
        <el-tab-pane :label="t('ai_dashboard.tab_recommend')" name="recommend">
          <div class="tab-content" v-loading="recommendLoading">
            <div class="recommend-grid" v-if="recommendProducts.length > 0">
              <div v-for="product in recommendProducts" :key="product.id" class="recommend-card">
                <div class="rc-image">
                  <img :src="getImageUrl(product.image)" @error="onImgError" />
                  <el-tag v-if="product.isHot" type="danger" size="small" class="hot-tag">{{ t('ai_dashboard.hot') }}</el-tag>
                </div>
                <div class="rc-info">
                  <div class="rc-name">{{ product.name }}</div>
                  <div class="rc-price">¥{{ product.price }}</div>
                  <div class="rc-meta">
                    <span>{{ t('ai_dashboard.label_stock') }}{{ product.stock || 0 }}</span>
                    <span>{{ t('ai_dashboard.label_sold') }}{{ product.sales || 0 }}</span>
                  </div>
                  <div class="rc-tags">
                    <el-tag v-if="product.tags" v-for="tag in (product.tags || '').split(',')" :key="tag" size="small" type="info">
                      {{ tag }}
                    </el-tag>
                  </div>
                </div>
              </div>
            </div>
            <el-empty v-else :description="t('ai_dashboard.no_recommend_data')" :image-size="100" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- AI 对话浮动按钮 -->
    <el-drawer v-model="chatDrawerVisible" :title="t('ai_dashboard.chat_title')" direction="rtl" size="420px">
      <div class="chat-drawer">
        <div class="chat-messages" ref="chatMsgRef">
          <div v-for="(msg, i) in chatMessages" :key="i" :class="['chat-msg', msg.role]">
            <div class="msg-content">{{ msg.content }}</div>
          </div>
          <div v-if="chatLoading" class="chat-msg ai">
            <div class="msg-content typing">{{ t('ai_dashboard.chat_thinking') }}</div>
          </div>
        </div>
        <div class="chat-input-box">
          <el-input v-model="chatInput" :placeholder="t('ai_dashboard.chat_input_placeholder')" 
            @keyup.enter="sendChat" :disabled="!aiStatus.connected" />
          <el-button type="primary" @click="sendChat" :disabled="!aiStatus.connected || chatLoading">
            {{ t('ai.send') }}
          </el-button>
        </div>
      </div>
    </el-drawer>

    <div class="chat-fab" @click="chatDrawerVisible = true">
      <el-icon :size="24"><ChatDotRound /></el-icon>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { DataAnalysis, Refresh, Download, Opportunity, InfoFilled, ChatDotRound } from '@element-plus/icons-vue'
import echarts from '@/utils/echarts'
import { getImageUrl } from '@/utils/image'
// 【R16】移除未使用的 forecastSales / getProductListForAI（本文件内零调用；
// 销售趋势 tab 用的是 getDashboardStats 的聚合数据）。这同时消掉了 R19 的唯一前端触发点。
import { getAIStatus, getInventoryWarning, generateReport, getRecommendations, getDashboardStats, chatWithAI } from '@/api/ai'

// 【R20】全页 i18n：模板文案、图表 legend/series/坐标轴名称、CSV 表头、
// ElMessage 与 console.error 的中文提示，全部走 t()。
// ⚠️ 两类「枚举」保持原样不翻译：① echarts 配置项（type: 'bar'/'line'/'pie'、
// trigger、shadow 等）是 API 参数，翻了图表会崩；② 后端返回的业务枚举
// （补货优先级「紧急」「重要」）是数据值，翻了比较逻辑会失效。
const { t } = useI18n()

// ---- 状态 ----
const activeTab = ref('warning')
const refreshing = ref(false)
const lastUpdateTime = ref('')
const aiStatus = ref({ connected: false })

// 仪表盘统计
const dashboardStats = ref(null)
const warningResult = ref(null)
const businessReport = ref(null)
const recommendProducts = ref([])
const salesTrendData = ref([])

// 加载状态
const warningLoading = ref(false)
const trendLoading = ref(false)
const reportLoading = ref(false)
const recommendLoading = ref(false)

// 趋势维度
const trendDimension = ref('daily')
const trendChartType = ref('line')

// 报告周期
const reportPeriod = ref('month')

// 图表实例
let inventoryChart = null
let salesTrendChart = null
let reportCategoryChart = null

// 【修复·P0-5】三个图表容器的模板 ref 必须在这里显式声明。
// 原代码用 document.querySelector('[ref="xxx"]') 查找容器 —— Vue 编译时
// 会把模板里的 ref 属性剥离并交给 ref 系统处理，DOM 上根本不存在
// [ref="..."] 这个属性，选择器永远匹配不到 → 三张图全部静默不渲染。
const inventoryChartRef = ref(null)
const salesTrendChartRef = ref(null)
const reportCategoryChartRef = ref(null)

// AI 对话
const chatDrawerVisible = ref(false)
const chatMessages = ref([{ role: 'ai', content: t('ai_dashboard.chat_welcome') }])
const chatInput = ref('')
const chatLoading = ref(false)
const chatMsgRef = ref(null)

// ---- 计算属性 ----
const statsCards = computed(() => {
  const ds = dashboardStats.value || {}
  return [
    { key: 'todaySales', label: t('ai_dashboard.stat_today_sales'), value: '¥' + (ds.todaySales || '0'), icon: 'Money', colorClass: 'card-blue', tabTarget: 'trend' },
    { key: 'todayOrders', label: t('ai_dashboard.stat_today_orders'), value: ds.todayOrders || 0, icon: 'Document', colorClass: 'card-green', tabTarget: 'trend' },
    { key: 'totalProducts', label: t('charts.total_products'), value: ds.totalProducts || 0, icon: 'Goods', colorClass: 'card-orange', tabTarget: 'warning' },
    { key: 'warningCount', label: t('inventory.warning'), value: ds.warningCount || 0, icon: 'Warning', colorClass: 'card-red', tabTarget: 'warning' }
  ]
})

const reportKPIs = computed(() => {
  const r = businessReport.value || {}
  const comp = r.comparison || {}
  return [
    { key: 'sales', label: t('ai_dashboard.kpi_total_sales'), value: '¥' + (r.totalSales || 0), change: comp.salesGrowth ? Number(comp.salesGrowth).toFixed(1) : null },
    { key: 'profit', label: t('ai_dashboard.kpi_total_profit'), value: '¥' + (r.totalProfit || 0) },
    { key: 'orders', label: t('ai_dashboard.kpi_orders'), value: (r.completedOrders || 0) + '/' + (r.totalOrders || 0) },
    { key: 'warning', label: t('inventory.warning'), value: r.warningInventoryCount || 0 }
  ]
})

const trendInsight = computed(() => {
  if (!salesTrendData.value.length) return ''
  const recent = salesTrendData.value.slice(-7)
  const sum = recent.reduce((s, d) => s + (d.amount || 0), 0)
  const avg = recent.length > 0 ? (sum / recent.length).toFixed(0) : 0
  if (avg > 0) return t('ai_dashboard.trend_insight_avg', { amount: avg })
  return t('ai_dashboard.no_recent_sales')
})

// ---- 数据加载 ----
const refreshAll = async () => {
  refreshing.value = true
  try {
    await Promise.all([
      loadDashboardStats(),
      loadAIStatus()
    ])
    lastUpdateTime.value = new Date().toLocaleTimeString()
  } finally {
    refreshing.value = false
  }
}

const loadAIStatus = async () => {
  try {
    const res = await getAIStatus()
    aiStatus.value = res.data || { connected: false }
  } catch (e) {
    aiStatus.value = { connected: false }
  }
}

const loadDashboardStats = async () => {
  try {
    const res = await getDashboardStats()
    if (res.code === 200) {
      dashboardStats.value = res.data
      salesTrendData.value = res.data.dailyTrend || []
    }
  } catch (e) {
    console.error(t('ai_dashboard.log_load_stats_failed'), e)
  }
}

const loadWarningAnalysis = async () => {
  warningLoading.value = true
  try {
    const res = await getInventoryWarning()
    if (res.code === 200) {
      warningResult.value = res.data
      await nextTick()
      renderInventoryChart()
    }
  } catch (e) {
    console.error(t('ai_dashboard.log_warning_failed'), e)
  } finally {
    warningLoading.value = false
  }
}

const loadSalesTrend = async () => {
  trendLoading.value = true
  try {
    await loadDashboardStats()
    await nextTick()
    renderSalesTrendChart()
  } catch (e) {
    console.error(t('ai_dashboard.log_trend_failed'), e)
  } finally {
    trendLoading.value = false
  }
}

const generateBusinessReport = async () => {
  reportLoading.value = true
  try {
    const res = await generateReport(reportPeriod.value)
    if (res.code === 200) {
      businessReport.value = res.data
      await nextTick()
      renderReportCategoryChart()
    }
  } catch (e) {
    console.error(t('ai_dashboard.log_report_failed'), e)
    ElMessage.error(t('ai_dashboard.report_gen_failed'))
  } finally {
    reportLoading.value = false
  }
}

const loadRecommendations = async () => {
  recommendLoading.value = true
  try {
    const res = await getRecommendations(null, 12)
    if (res.code === 200) {
      recommendProducts.value = res.data || []
    }
  } catch (e) {
    console.error(t('ai_dashboard.log_recommend_failed'), e)
  } finally {
    recommendLoading.value = false
  }
}

// ---- 图表渲染 ----
const renderInventoryChart = () => {
  const el = inventoryChartRef.value
  if (!el) return
  
  if (inventoryChart) inventoryChart.dispose()
  inventoryChart = echarts.init(el)
  
  const replenishments = warningResult.value?.replenishments || []
  if (replenishments.length === 0) {
    inventoryChart.setOption({
      title: { text: t('ai_dashboard.chart_good_stock'), left: 'center', top: 'center', textStyle: { color: '#67c23a', fontSize: 16 } }
    })
    return
  }
  
  const names = replenishments.slice(0, 10).map(r => r.productName)
  const stocks = replenishments.slice(0, 10).map(r => r.currentStock)
  const warnings = replenishments.slice(0, 10).map(r => r.warningQuantity)
  const recommended = replenishments.slice(0, 10).map(r => r.recommendedQuantity)
  
  const recommendQtyLabel = t('ai_dashboard.legend_recommend_qty')
  inventoryChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: [t('charts.current_stock'), t('charts.warning_threshold'), recommendQtyLabel], bottom: 0 },
    grid: { left: '3%', right: '4%', bottom: '12%', top: '8%', containLabel: true },
    xAxis: { type: 'category', data: names, axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', name: t('ai_dashboard.axis_quantity') },
    series: [
      { name: t('charts.current_stock'), type: 'bar', data: stocks, itemStyle: { color: '#f56c6c' }, barWidth: '40%' },
      { name: t('charts.warning_threshold'), type: 'bar', data: warnings, itemStyle: { color: '#e6a23c' }, barWidth: '40%' },
      { name: recommendQtyLabel, type: 'bar', data: recommended, itemStyle: { color: '#409eff' }, barWidth: '40%' }
    ]
  })
}

const renderSalesTrendChart = () => {
  const el = salesTrendChartRef.value
  if (!el) return
  
  if (salesTrendChart) salesTrendChart.dispose()
  salesTrendChart = echarts.init(el)
  
  const data = salesTrendData.value || []
  if (data.length === 0) {
    salesTrendChart.setOption({
      title: { text: t('ai_dashboard.no_sales_data'), left: 'center', top: 'center', textStyle: { color: '#999' } }
    })
    return
  }
  
  // 根据维度聚合数据
  let aggregated = []
  if (trendDimension.value === 'daily') {
    aggregated = data.map(d => ({ label: d.date?.slice(-5) || d.date, value: Number(d.amount) || 0 }))
  } else if (trendDimension.value === 'weekly') {
    const weeks = {}
    data.forEach(d => {
      const date = new Date(d.date)
      const weekStart = new Date(date)
      weekStart.setDate(date.getDate() - date.getDay())
      const key = weekStart.toISOString().slice(0, 10)
      weeks[key] = (weeks[key] || 0) + (Number(d.amount) || 0)
    })
    aggregated = Object.entries(weeks).map(([k, v]) => ({ label: k.slice(-5), value: v }))
  } else {
    const months = {}
    data.forEach(d => {
      const key = d.date?.slice(0, 7) || ''
      months[key] = (months[key] || 0) + (Number(d.amount) || 0)
    })
    aggregated = Object.entries(months).map(([k, v]) => ({ label: k, value: v }))
  }
  
  salesTrendChart.setOption({
    tooltip: { trigger: 'axis', formatter: p => `${p[0].axisValue}<br/>${t('ai_dashboard.tooltip_sales')}: ¥${p[0].value}` },
    grid: { left: '3%', right: '4%', bottom: '8%', top: '8%', containLabel: true },
    xAxis: { type: 'category', data: aggregated.map(d => d.label), axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', name: t('ai_dashboard.axis_sales_yuan') },
    series: [{
      type: trendChartType.value,
      data: aggregated.map(d => d.value),
      smooth: true,
      itemStyle: { color: '#409eff' },
      areaStyle: trendChartType.value === 'line' ? { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: 'rgba(64,158,255,0.3)' },
        { offset: 1, color: 'rgba(64,158,255,0.05)' }
      ]) } : undefined
    }]
  })
}

const renderReportCategoryChart = () => {
  const el = reportCategoryChartRef.value
  if (!el) return
  
  if (reportCategoryChart) reportCategoryChart.dispose()
  reportCategoryChart = echarts.init(el)
  
  const catSales = businessReport.value?.categorySales || {}
  const entries = Object.entries(catSales).sort((a, b) => b[1] - a[1]).slice(0, 6)
  if (entries.length === 0) {
    reportCategoryChart.setOption({
      title: { text: t('ai_dashboard.no_category_data'), left: 'center', top: 'center', textStyle: { color: '#999' } }
    })
    return
  }
  
  reportCategoryChart.setOption({
    title: { text: t('ai_dashboard.chart_top_category'), left: 'center', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'item' },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      data: entries.map(([name, value]) => ({ name, value })),
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' } }
    }]
  })
}

// ---- AI 对话 ----
const sendChat = async () => {
  if (!chatInput.value.trim()) return
  const msg = chatInput.value.trim()
  chatMessages.value.push({ role: 'user', content: msg })
  chatInput.value = ''
  chatLoading.value = true
  
  await nextTick()
  if (chatMsgRef.value) {
    chatMsgRef.value.scrollTop = chatMsgRef.value.scrollHeight
  }
  
  try {
    const res = await chatWithAI(msg)
    chatMessages.value.push({ role: 'ai', content: res.data || t('ai_dashboard.chat_no_reply') })
  } catch (e) {
    chatMessages.value.push({ role: 'ai', content: t('ai_dashboard.chat_service_unavailable') })
  } finally {
    chatLoading.value = false
    await nextTick()
    if (chatMsgRef.value) {
      chatMsgRef.value.scrollTop = chatMsgRef.value.scrollHeight
    }
  }
}

// ---- 导出 ----
const exportReport = () => {
  const ds = dashboardStats.value || {}
  const headers = [t('ai_dashboard.csv_metric'), t('ai_dashboard.csv_value')]
  const rows = [
    [t('ai_dashboard.stat_today_sales'), '¥' + (ds.todaySales || '0')],
    [t('ai_dashboard.stat_today_orders'), ds.todayOrders || 0],
    [t('ai_dashboard.csv_week_sales'), '¥' + (ds.weekSales || '0')],
    [t('ai_dashboard.csv_month_sales'), '¥' + (ds.monthSales || '0')],
    [t('charts.total_products'), ds.totalProducts || 0],
    [t('ai_dashboard.csv_warning_count'), ds.warningCount || 0],
    [t('ai_dashboard.csv_generate_time'), ds.generateTime || '']
  ]
  
  let csv = '\uFEFF' + headers.join(',') + '\n'
  rows.forEach(r => { csv += r.join(',') + '\n' })
  
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${t('ai_dashboard.report_file_prefix')}_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(t('ai_dashboard.export_success'))
}

// ---- Tab 切换 ----
const handleTabChange = (tab) => {
  if (tab === 'warning') loadWarningAnalysis()
  else if (tab === 'trend') loadSalesTrend()
  else if (tab === 'report') generateBusinessReport()
  else if (tab === 'recommend') loadRecommendations()
}

// ---- 图片处理 ----
const onImgError = (e) => {
  e.target.src = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxMDAiIGhlaWdodD0iMTAwIiB2aWV3Qm94PSIwIDAgMTAwIDEwMCI+PHJlY3Qgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiIGZpbGw9IiNmNWY3ZmEiIHJ4PSI4Ii8+PHRleHQgeD0iNTAiIHk9IjU1IiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBmaWxsPSIjOTA5MzkiIGZvbnQtc2l6ZT0iMTIiIGZvbnQtZmFtaWx5PSJBcmlhbCI+Tm8gSW1hZ2U8L3RleHQ+PC9zdmc+'
}

// ---- 生命周期 ----
const resizeHandler = () => {
  inventoryChart?.resize()
  salesTrendChart?.resize()
  reportCategoryChart?.resize()
}

onMounted(async () => {
  await refreshAll()
  loadWarningAnalysis()
  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  window.removeEventListener('resize', resizeHandler)
  inventoryChart?.dispose()
  salesTrendChart?.dispose()
  reportCategoryChart?.dispose()
})
</script>

<style scoped>
.ai-dashboard {
  padding: 16px;
  max-width: 1500px;
  margin: 0 auto;
}

.ai-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.last-update {
  font-size: 12px;
  color: #909399;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 统计卡片 */
.stats-row {
  margin-bottom: 16px;
}

.stat-card {
  background: white;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0,0,0,0.1);
}

.stat-icon-wrap {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-blue .stat-icon-wrap { background: rgba(64,158,255,0.1); color: #409eff; }
.card-green .stat-icon-wrap { background: rgba(103,194,58,0.1); color: #67c23a; }
.card-orange .stat-icon-wrap { background: rgba(230,162,60,0.1); color: #e6a23c; }
.card-red .stat-icon-wrap { background: rgba(245,108,108,0.1); color: #f56c6c; }

.stat-body { flex: 1; }
.stat-value { font-size: 24px; font-weight: 700; color: #303133; }
.stat-label { font-size: 13px; color: #909399; margin-top: 2px; }
.stat-trend { font-size: 12px; margin-top: 4px; }
.stat-trend.up { color: #f56c6c; }
.stat-trend.down { color: #67c23a; }

/* 主内容区 */
.main-content {
  border-radius: 12px;
}

.tab-content {
  min-height: 400px;
}

.chart-box, .warning-summary {
  background: #fafbfc;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
}

.chart-header, .summary-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.chart-title { font-weight: 600; font-size: 15px; }

.warning-list { max-height: 300px; overflow-y: auto; }

.warning-item {
  background: white;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 8px;
  border-left: 4px solid #e6a23c;
}

.warning-item.urgent { border-left-color: #f56c6c; background: #fef0f0; }

.wi-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.wi-name { font-weight: 600; }

.wi-detail {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #606266;
}

.wi-detail .highlight { color: #f56c6c; }

.ai-advice-box {
  background: linear-gradient(135deg, #f0f9ff, #ecfdf5);
  border-radius: 8px;
  padding: 16px;
  margin-top: 12px;
}

.advice-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.advice-text {
  color: #4b5563;
  line-height: 1.8;
  white-space: pre-wrap;
}

.chart-actions {
  margin-bottom: 12px;
  display: flex;
}

.trend-insight {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 14px;
  background: #f0f9ff;
  border-radius: 6px;
  color: #606266;
}

/* 报告 */
.report-toolbar { margin-bottom: 16px; }
.report-kpis { margin-bottom: 16px; }

.report-kpi-card {
  text-align: center;
  background: #fafbfc;
  border-radius: 8px;
  padding: 16px;
}

.kpi-value { font-size: 22px; font-weight: 700; }
.kpi-label { font-size: 12px; color: #909399; margin-top: 4px; }
.kpi-change { font-size: 12px; margin-top: 4px; }
.kpi-change.positive { color: #f56c6c; }
.kpi-change.negative { color: #67c23a; }

.top-products {
  background: #fafbfc;
  border-radius: 8px;
  padding: 16px;
}

.top-products h4 { margin: 0 0 12px; font-size: 14px; }

.top-product-item {
  display: flex;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px dashed #eee;
  font-size: 13px;
}

.rank {
  width: 24px;
  text-align: center;
  font-weight: 600;
  color: #909399;
}

.rank.top3 { color: #e6a23c; }

.name { flex: 1; }
.sales { color: #909399; }

/* 推荐 */
.recommend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.recommend-card {
  background: white;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #ebeef5;
  transition: all 0.3s;
}

.recommend-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.1);
}

.rc-image {
  position: relative;
  height: 160px;
  background: #f5f7fa;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rc-image img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.hot-tag {
  position: absolute;
  top: 8px;
  right: 8px;
}

.rc-info { padding: 12px; }

.rc-name {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rc-price { font-size: 18px; font-weight: 700; color: #f56c6c; margin-bottom: 6px; }

.rc-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.rc-tags { display: flex; gap: 4px; flex-wrap: wrap; }

/* 聊天 */
.chat-fab {
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #409eff;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(64,158,255,0.4);
  z-index: 100;
  transition: all 0.3s;
}

.chat-fab:hover { transform: scale(1.1); }

.chat-drawer { display: flex; flex-direction: column; height: 100%; }

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.chat-msg {
  margin-bottom: 12px;
  max-width: 85%;
}

.chat-msg.user { margin-left: auto; }
.chat-msg.ai { margin-right: auto; }

.msg-content {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.chat-msg.user .msg-content { background: #ecf5ff; }
.chat-msg.ai .msg-content { background: #f5f7fa; }

.typing { color: #909399; font-style: italic; }

.chat-input-box {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid #ebeef5;
}

.chat-input-box .el-input { flex: 1; }

@media (max-width: 768px) {
  .stats-row { flex-direction: column; }
  .recommend-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
