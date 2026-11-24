<template>
  <div class="merchant-dashboard">
    <div class="live-indicator" v-if="isLive">
      <span class="live-dot"></span>
      <span>{{ $t('dashboard.realtime_update') }}</span>
      <span class="update-time">{{ $t('dashboard.last_update') }}: {{ lastUpdateTime }}</span>
    </div>

    <el-row :gutter="20" class="stats-row">
      <el-col :xs="24" :sm="12" :lg="6" v-for="(stat, index) in statCards" :key="stat.label">
        <div
          class="stat-card-wrapper"
          :style="{ '--card-delay': `${index * 0.1}s` }"
          @click="handleStatCardClick(stat)">
          <div class="stat-card">
            <div class="stat-icon" :style="{ background: stat.gradient }">
              <el-icon :size="28" color="#fff" />
            </div>
            <div class="stat-content">
              <div class="stat-value-wrapper">
                <span v-if="stat.prefix" class="stat-prefix">{{ stat.prefix }}</span>
                <span class="stat-value" :class="{ 'animate-number': isRefreshing }">
                  {{ formatStatValue(stat.value) }}
                </span>
              </div>
              <div class="stat-label">{{ stat.label }}</div>
              <div v-if="stat.trend !== undefined" class="stat-trend" :class="stat.trendClass">
                {{ stat.trendIcon }} {{ Math.abs(stat.trend) }}%
              </div>
            </div>
            <div class="stat-decoration"></div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="16">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <div class="chart-header">
              <div class="chart-title">
                <span>{{ $t('dashboard.sales_trend') }}</span>
                <span class="chart-date-range">{{ currentWeekRange }}</span>
              </div>
              <div class="chart-actions">
                <!-- 迁移说明（2026-09-20）：type="text" 在 Element Plus 3.0 将移除，
                     官方替代为 link。写法与项目其余页面保持一致（type="X" link）。
                     本组按钮的文字色由下方 .chart-actions .el-button 覆盖为
                     --text-secondary / 选中态 --accent-color，故不受 primary 基色影响。 -->
                <el-button
                  type="primary"
                  link
                  size="small"
                  :class="{ active: chartType === 'bar' }"
                  @click="chartType = 'bar'">
                  {{ $t('dashboard.bar_chart') }}
                </el-button>
                <el-button
                  type="primary"
                  link
                  size="small"
                  :class="{ active: chartType === 'line' }"
                  @click="chartType = 'line'">
                  {{ $t('dashboard.line_chart') }}
                </el-button>
              </div>
            </div>
          </template>
          <div ref="salesChartRef" class="chart-container"></div>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card class="quick-actions-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>{{ $t('dashboard.quick_actions') }}</span>
            </div>
          </template>
          <div class="quick-actions-grid">
            <el-button
              v-for="action in quickActions"
              :key="action.path"
              class="action-btn"
              :type="action.type"
              @click="$router.push(action.path)">
              <el-icon :size="20" />
              <span>{{ action.label }}</span>
            </el-button>
          </div>
        </el-card>

        <el-card class="warning-card" shadow="hover" style="margin-top: 20px">
          <template #header>
            <div class="card-header">
              <span>{{ $t('dashboard.inventory_warning') }}</span>
              <el-badge :value="warningItems.length" type="danger" v-if="warningItems.length > 0">
                <span style="color: #303133;">{{ $t('dashboard.warning_products') }}</span>
              </el-badge>
            </div>
          </template>
          <div class="warning-list" v-loading="isRefreshing">
            <div
              v-for="(item, index) in warningItems"
              :key="item.id || item.productId || index"
              class="warning-item"
              :style="{ '--item-delay': `${index * 0.1}s` }"
              @click="goToInventory(item)">
              <div class="warning-image">
                <img
                  :src="getProductImageUrl(item.productImage)"
                  :alt="item.productName || item.name"
                  @error="handleImageError($event)" />
              </div>
              <div class="warning-content">
                <div class="warning-name">{{ item.productName || item.name }}</div>
                <div class="warning-detail">
                  <el-tag :type="getWarningTagType(item.quantity || item.stock)" size="small">
                    {{ $t('dashboard.stock_colon') }}{{ item.quantity !== undefined ? item.quantity : item.stock }}
                  </el-tag>
                  <el-tag type="danger" size="small">{{ $t('dashboard.urgent') }}</el-tag>
                </div>
              </div>
              <el-icon class="arrow-icon" :size="16" color="#c0c4cc" />
            </div>
            <div v-if="warningItems.length === 0 && !isRefreshing" class="empty-warning">
              <div class="empty-icon">
                <el-icon :size="48" color="#67c23a" />
              </div>
              <span>{{ $t('dashboard.no_warning') }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import echarts from '@/utils/echarts'
import { getProductList } from '@/api/product'
import { getWarningInventories } from '@/api/inventory'
import { getDashboardStats } from '@/api/ai'
import { getImageUrl } from '@/utils/image'
import { useSettingsStore } from '@/stores/settings'

const router = useRouter()
const { t } = useI18n()
const settingsStore = useSettingsStore()

const isLive = ref(true)
const isRefreshing = ref(false)
const lastUpdateTime = ref('--')
let updateInterval = null
let chart = null

const chartType = ref('bar')
const salesChartRef = ref(null)

const isDark = computed(() => settingsStore.theme === 'dark')

const chartColors = computed(() => {
  if (isDark.value) {
    return {
      primary: '#64b4ff',
      secondary: '#8ce99a',
      axisLine: '#4a4a6a',
      axisLabel: '#b8b8d8',
      splitLine: '#2d3a5a',
      legendText: '#f0f0fa',
      tooltipBg: 'rgba(30, 30, 50, 0.95)',
      tooltipText: '#f5f5ff'
    }
  }
  return {
    primary: '#409eff',
    secondary: '#67c23a',
    axisLine: '#e4e7ed',
    axisLabel: '#606266',
    splitLine: '#f0f0f0',
    legendText: '#3c3c46',
    tooltipBg: 'rgba(255, 255, 255, 0.95)',
    tooltipText: '#1e1e2e'
  }
})

const stats = ref({
  totalProducts: 0,
  todayOrders: 0,
  todaySales: 0,
  warningCount: 0,
  totalOrders: 0,
  avgOrderValue: 0,
  customerCount: 0,
  conversionRate: 3.2
})

const warningItems = ref([])

const currentWeekRange = computed(() => {
  const now = new Date()
  const dayOfWeek = now.getDay() || 7
  const monday = new Date(now)
  monday.setDate(now.getDate() - dayOfWeek + 1)
  const sunday = new Date(monday)
  sunday.setDate(monday.getDate() + 6)

  const formatDate = (date) => {
    return `${date.getMonth() + 1}/${date.getDate()}`
  }

  return `${formatDate(monday)} - ${formatDate(sunday)}`
})

// 【数据可视化修复·2026-09-23】周趋势改为 /ai.dashboard 的真实数据驱动
// （周一 ~ 周日的每日销售额 / 订单量）。原为写死的示例数组
// [3200, 4100, …] / [12, 15, …]，与真实售卖无关。
const salesData = ref({
  // 【i18n】weekDays 不再是硬编码数组：改由 weekdayLabels 计算属性提供，
  // 跟随语言切换（原实现把中文写在 ref 初值里，切语言后 x 轴仍是中文）。
  salesAmount: [0, 0, 0, 0, 0, 0, 0],
  orderCount: [0, 0, 0, 0, 0, 0, 0]
})

// 周一 ~ 周日的本地化标签（x 轴）
const weekdayLabels = computed(() => [
  t('charts.weekday_mon'),
  t('charts.weekday_tue'),
  t('charts.weekday_wed'),
  t('charts.weekday_thu'),
  t('charts.weekday_fri'),
  t('charts.weekday_sat'),
  t('charts.weekday_sun')
])

// 真实日趋势（供趋势百分比推导：今日 vs 昨日）
const dailyTrend = ref([])

// 趋势百分比：无历史数据时不展示（原为 5.2 / 12.8 / 8.5 / -3.2 等写死装饰数字）
const trendProps = (pct) => {
  if (pct === undefined || pct === null || Number.isNaN(pct)) {
    return { trend: undefined }
  }
  return {
    trend: pct,
    trendClass: pct >= 0 ? 'trend-up' : 'trend-down',
    trendIcon: pct >= 0 ? '↑' : '↓'
  }
}

const pctVsYesterday = (getVal) => {
  const days = dailyTrend.value
  if (!days || days.length < 2) return undefined
  const todayVal = Number(getVal(days[days.length - 1]) || 0)
  const yestVal = Number(getVal(days[days.length - 2]) || 0)
  if (!yestVal) return undefined
  return Number((((todayVal - yestVal) / yestVal) * 100).toFixed(1))
}

const statCards = computed(() => [
  {
    label: t('dashboard.total_products'),
    value: stats.value.totalProducts,
    color: '#409eff',
    gradient: 'linear-gradient(135deg, #409eff 0%, #667eea 100%)',
    prefix: '',
    ...trendProps(undefined),
    link: '/merchant/products'
  },
  {
    label: t('dashboard.today_orders'),
    value: stats.value.todayOrders,
    color: '#67c23a',
    gradient: 'linear-gradient(135deg, #67c23a 0%, #85ce61 100%)',
    prefix: '',
    ...trendProps(pctVsYesterday(d => d.orders)),
    link: '/merchant/orders'
  },
  {
    label: t('dashboard.today_sales'),
    value: stats.value.todaySales,
    color: '#e6a23c',
    gradient: 'linear-gradient(135deg, #e6a23c 0%, #f0c78a 100%)',
    prefix: '¥',
    ...trendProps(pctVsYesterday(d => d.amount)),
    link: '/merchant/orders'
  },
  {
    label: t('dashboard.inventory_warning'),
    value: stats.value.warningCount,
    color: '#f56c6c',
    gradient: 'linear-gradient(135deg, #f56c6c 0%, #f89898 100%)',
    prefix: '',
    ...trendProps(undefined),
    link: '/merchant/inventory'
  }
])

const quickActions = [
  { label: t('dashboard.product_manage'), type: 'primary', path: '/merchant/products' },
  { label: t('dashboard.inventory_manage'), type: 'success', path: '/merchant/inventory' },
  { label: t('dashboard.order_manage'), type: 'warning', path: '/merchant/orders' },
  { label: t('dashboard.data_report'), type: 'info', path: '/merchant/echarts' }
]

const formatStatValue = (value) => {
  if (typeof value === 'number') {
    if (value >= 10000) {
      return (value / 10000).toFixed(1) + 'w'
    }
    return value.toLocaleString()
  }
  return value
}

const getProductImageUrl = (image) => {
  if (!image) return ''
  return getImageUrl(image, 'Product Image')
}

const handleImageError = (event) => {
  event.target.style.display = 'none'
}

const getWarningTagType = (quantity) => {
  const q = Number(quantity) || 0
  if (q <= 10) return 'danger'
  if (q <= 30) return 'warning'
  return 'success'
}

const loadDashboardData = async (isInitial = false) => {
  isRefreshing.value = true

  try {
    const [productRes, warningRes, dashRes] = await Promise.all([
      getProductList({ page: 1, pageSize: isInitial ? 20 : 10 }),
      getWarningInventories(),
      getDashboardStats()
    ])

    const products = productRes.data?.records || productRes.data || []
    const warnings = warningRes.data?.records || warningRes.data || []
    const dash = dashRes.data || {}

    stats.value.totalProducts = dash.totalProducts ?? (productRes.data?.total || products.length)
    stats.value.warningCount = dash.warningCount ?? warnings.length
    // 【数据可视化修复·2026-09-23】今日销售 / 今日订单改由真实订单聚合驱动：
    // 原 todaySales = Σ(price × product.sales)（product.sales 是下单链路从不更新的
    // 静态字段），todayOrders = Math.floor(Math.random() * 20) + 5（纯随机假数）。
    stats.value.todaySales = Number(dash.todaySales || 0)
    stats.value.todayOrders = dash.todayOrders || 0
    dailyTrend.value = dash.dailyTrend || []

    // 周趋势（周一 ~ 周日）：真实每日销售额 / 有效销售订单量
    const weekly = dash.weeklyTrend || []
    salesData.value = {
      salesAmount: weekly.map(p => Number(p.amount || 0)),
      orderCount: weekly.map(p => Number(p.orders || 0))
    }

    warningItems.value = warnings.slice(0, 5)

    lastUpdateTime.value = new Date().toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    })

    if (isInitial) {
      await nextTick()
      updateChart()
    }
  } catch (error) {
    console.error('加载仪表盘数据失败:', error)
  } finally {
    isRefreshing.value = false
  }
}

// 【修复】resizeHandler 必须存住 —— 添加与移除要用同一个引用
let resizeHandler = null

const initChart = () => {
  if (!salesChartRef.value) return

  // 幂等：重复进入本页时不要在同一 DOM 上重复 init
  if (chart && !chart.isDisposed()) {
    chart.dispose()
  }
  chart = echarts.init(salesChartRef.value, null, { renderer: 'svg' })
  updateChart()

  resizeHandler = debounce(handleResize, 250)
  window.addEventListener('resize', resizeHandler)
}

/**
 * 防抖。定时器改为闭包内私有，并暴露 cancel()。
 *
 * 【原实现的坑】
 * 1) 定时器用的是模块级变量 resizeTimeout，多个防抖函数会互相干扰；
 * 2) 返回值没有被任何地方保存，导致 addEventListener 加了它、
 *    removeEventListener 却传了原始 handleResize —— 引用不同，
 *    移除静默失效。
 */
const debounce = (fn, delay) => {
  let timer = null
  const wrapped = (...args) => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fn(...args)
    }, delay)
  }
  wrapped.cancel = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }
  return wrapped
}

/**
 * 【原 bug】这里只写 `chart?.resize()`：组件卸载并 chart.dispose() 之后，
 * 残留的 resize 监听器仍会调进来，于是控制台刷屏
 *   [ECharts] Instance ec_xxx has been disposed
 * 现在加 disposed 判断兜底。
 */
const handleResize = () => {
  if (chart && !chart.isDisposed()) {
    chart.resize()
  }
}

const getChartOption = () => {
  const colors = chartColors.value
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross'
      },
      backgroundColor: colors.tooltipBg,
      textStyle: {
        color: colors.tooltipText
      },
      borderColor: colors.axisLine,
      borderWidth: 1
    },
    legend: {
      // 【i18n】legend.data 必须与下面 series[].name 完全一致（含大小写与空格），
      // 否则 echarts 关联不上、点击图例无法切换显隐。两者都走同一对键。
      data: [t('charts.sales_amount'), t('charts.order_count')],
      top: 10,
      textStyle: {
        color: colors.legendText
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: weekdayLabels.value,
      axisLine: {
        lineStyle: { color: colors.axisLine }
      },
      axisLabel: {
        color: colors.axisLabel
      },
      axisTick: {
        lineStyle: { color: colors.axisLine }
      }
    },
    yAxis: [
      {
        type: 'value',
        name: t('charts.sales_amount_yuan'),
        position: 'left',
        axisLine: { show: true, lineStyle: { color: colors.primary } },
        axisLabel: { color: colors.axisLabel },
        axisTick: { lineStyle: { color: colors.axisLine } },
        splitLine: { lineStyle: { color: colors.splitLine } },
        nameTextStyle: {
          color: colors.axisLabel
        }
      },
      {
        type: 'value',
        name: t('charts.order_count'),
        position: 'right',
        axisLine: { show: true, lineStyle: { color: colors.secondary } },
        axisLabel: { color: colors.axisLabel },
        axisTick: { lineStyle: { color: colors.axisLine } },
        splitLine: { show: false },
        nameTextStyle: {
          color: colors.axisLabel
        }
      }
    ],
    series: [
      {
        name: t('charts.sales_amount'),
        type: chartType.value,
        data: salesData.value.salesAmount,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: colors.primary },
            { offset: 1, color: isDark.value ? '#4a9eff' : '#667eea' }
          ]),
          borderRadius: [4, 4, 0, 0]
        },
        smooth: true,
        barWidth: '40%',
        emphasis: {
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: isDark.value ? '#8ac4ff' : '#60a5fa' },
              { offset: 1, color: colors.primary }
            ])
          }
        }
      },
      {
        name: t('charts.order_count'),
        type: 'line',
        yAxisIndex: 1,
        data: salesData.value.orderCount,
        itemStyle: { color: colors.secondary },
        lineStyle: { width: 3 },
        symbolSize: 8,
        smooth: true,
        emphasis: {
          itemStyle: {
            color: isDark.value ? '#a8f0ad' : '#85ce61'
          }
        }
      }
    ]
  }
}

const updateChart = () => {
  if (!chart) return
  chart.setOption(getChartOption(), true)
}

const handleStatCardClick = (stat) => {
  if (stat.link) {
    router.push(stat.link)
  }
}

const goToInventory = (productId) => {
  router.push('/merchant/inventory')
}

watch(chartType, () => {
  updateChart()
})

watch(() => settingsStore.theme, () => {
  updateChart()
})

onMounted(async () => {
  lastUpdateTime.value = new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })

  await nextTick()
  initChart()
  await loadDashboardData(true)

  updateInterval = setInterval(() => {
    loadDashboardData(false)
  }, 120000)
})

onUnmounted(() => {
  if (updateInterval) {
    clearInterval(updateInterval)
  }
  // 【修复】原先这里 clearTimeout(resizeTimeout) 清的是模块级共享定时器，
  // 而且 removeEventListener 传的是 handleResize（从未被添加过的原始函数），
  // 移除静默失效 —— 监听器会一直累积，并在 dispose 后继续被触发。
  // 现在：先 cancel 掉待执行的防抖，再用同一个引用移除监听，最后置空引用。
  if (resizeHandler) {
    resizeHandler.cancel()
    window.removeEventListener('resize', resizeHandler)
    resizeHandler = null
  }
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.merchant-dashboard {
  padding: 24px;
  background: var(--bg-secondary);
  min-height: calc(100vh - 88px);
  position: relative;
  z-index: 1;
}

.live-indicator {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  background: var(--glass-bg);
  /* 不再叠加 backdrop-filter：本页整片都位于 .main-content 的模糊层之上，
     继续嵌套模糊会让滚动时每张卡各自重新采样背景（本项目最重的性能写法）。
     改用玻璃底色 + 高光边框，观感一致，开销几乎为零。 */
  background-image: var(--glass-sheen-gradient);
  color: var(--glass-text-primary);
  border-radius: 16px;
  margin-bottom: 24px;
  font-size: 14px;
  border: 1px solid var(--glass-border);
  box-shadow:
    0 10px 30px var(--glass-shadow),
    inset 0 1px 1px var(--glass-highlight);
}

.live-dot {
  width: 8px;
  height: 8px;
  background: #4ade80;
  border-radius: 50%;
  animation: pulse 2s infinite;
  box-shadow: 0 0 10px rgba(74, 222, 128, 0.5);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.update-time {
  margin-left: auto;
  opacity: 0.8;
  color: var(--glass-text-muted);
}

.stats-row {
  margin-bottom: 20px;
}

.stat-card-wrapper {
  animation: fadeInUp 0.5s ease-out forwards;
  opacity: 0;
  transform: translateY(20px);
}

@keyframes fadeInUp {
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.stat-card {
  background: var(--glass-bg);
  /* 同上：统计卡数量多（一屏十几张），去掉嵌套模糊 */
  background-image: var(--glass-sheen-gradient);
  border-radius: 16px;
  padding: 20px;
  position: relative;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
  box-shadow: 0 10px 25px var(--glass-shadow);
  min-height: 120px;
  border: 1px solid var(--glass-border);
}

.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 15px 35px var(--glass-shadow-hover);
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  float: left;
  margin-right: 16px;
  transition: transform 0.3s ease;
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.2);
}

.stat-card:hover .stat-icon {
  transform: scale(1.1);
}

.stat-content {
  overflow: hidden;
}

.stat-value-wrapper {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.stat-prefix {
  font-size: 16px;
  color: var(--glass-text-secondary);
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: var(--glass-text-primary);
  text-shadow: 0 1px 2px var(--glass-shadow);
}

.stat-value.animate-number {
  animation: numberPulse 0.3s ease;
}

@keyframes numberPulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.05); }
}

.stat-label {
  font-size: 14px;
  color: var(--glass-text-secondary);
  margin-top: 4px;
}

.stat-trend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  margin-top: 8px;
  padding: 2px 8px;
  border-radius: 12px;
}

.stat-trend.trend-up {
  background: rgba(140, 233, 154, 0.15);
  color: #8ce99a;
}

.stat-trend.trend-down {
  background: rgba(255, 135, 135, 0.15);
  color: #ff8787;
}

.stat-decoration {
  position: absolute;
  top: -20px;
  right: -20px;
  width: 100px;
  height: 100px;
  background: rgba(64, 158, 255, 0.1);
  border-radius: 50%;
}

.chart-card {
  min-height: 400px;
  background: var(--glass-bg);
  /* 去掉嵌套模糊，改由底色 + 高光承担玻璃观感 */
  background-image: var(--glass-sheen-gradient);
  border: 1px solid var(--glass-border);
  border-radius: var(--glass-radius-lg);
  box-shadow:
    0 8px 32px var(--glass-shadow),
    inset 0 1px 0 var(--glass-highlight-soft);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.chart-card:hover {
  box-shadow:
    0 16px 48px var(--glass-shadow-hover),
    inset 0 1px 0 var(--glass-highlight);
}

.chart-card :deep(.el-card__header) {
  background: var(--glass-bg);
  border-bottom: 1px solid var(--glass-border);
}

.chart-card :deep(.el-card__body) {
  background: var(--glass-bg);
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.chart-date-range {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 400;
}

.chart-actions {
  display: flex;
  gap: 10px;
}

.chart-actions .el-button {
  color: var(--text-secondary);
}

.chart-actions .el-button.active {
  color: var(--accent-color);
  font-weight: 600;
}

.chart-container {
  height: 350px;
  width: 100%;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.quick-actions-card {
  background: var(--glass-bg);
  /* 去掉嵌套模糊，改由底色 + 高光承担玻璃观感 */
  background-image: var(--glass-sheen-gradient);
  border: 1px solid var(--glass-border);
  border-radius: var(--glass-radius-lg);
  box-shadow:
    0 8px 32px var(--glass-shadow),
    inset 0 1px 0 var(--glass-highlight-soft);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.quick-actions-card:hover {
  box-shadow:
    0 16px 48px var(--glass-shadow-hover),
    inset 0 1px 0 var(--glass-highlight);
}

.quick-actions-card :deep(.el-card__header) {
  background: var(--glass-bg);
  border-bottom: 1px solid var(--glass-border);
}

.quick-actions-card :deep(.el-card__body) {
  background: var(--glass-bg);
}

.quick-actions-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px 12px;
  border-radius: 12px;
  transition: all 0.3s ease;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
}

.action-btn:hover {
  transform: translateY(-3px);
  background: var(--accent-light);
  border-color: var(--accent-color);
}

.warning-card {
  min-height: 350px;
  background: var(--glass-bg);
  /* 去掉嵌套模糊，改由底色 + 高光承担玻璃观感 */
  background-image: var(--glass-sheen-gradient);
  border: 1px solid var(--glass-border);
  border-radius: var(--glass-radius-lg);
  box-shadow:
    0 8px 32px var(--glass-shadow),
    inset 0 1px 0 var(--glass-highlight-soft);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.warning-card:hover {
  box-shadow:
    0 16px 48px var(--glass-shadow-hover),
    inset 0 1px 0 var(--glass-highlight);
}

.warning-card :deep(.el-card__header) {
  background: var(--glass-bg);
  border-bottom: 1px solid var(--glass-border);
}

.warning-card :deep(.el-card__body) {
  background: var(--glass-bg);
}

.warning-list {
  max-height: 300px;
  overflow-y: auto;
}

.warning-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: rgba(255, 108, 108, 0.15);
  border-radius: 10px;
  margin-bottom: 8px;
  animation: fadeInLeft 0.3s ease-out forwards;
  opacity: 0;
  transform: translateX(-20px);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 1px solid rgba(255, 108, 108, 0.2);
}

@keyframes fadeInLeft {
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

.warning-item:hover {
  background: rgba(255, 108, 108, 0.25);
  transform: translateX(4px);
}

.warning-image {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  background: rgba(255, 108, 108, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.warning-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.warning-content {
  flex: 1;
  min-width: 0;
}

.warning-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.warning-detail {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 6px;
  display: flex;
  gap: 6px;
}

.warning-detail :deep(.el-tag) {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
}

.warning-detail :deep(.el-tag--danger) {
  background: rgba(255, 108, 108, 0.3);
  border-color: rgba(255, 108, 108, 0.4);
  color: #ff8787;
}

.arrow-icon {
  flex-shrink: 0;
  color: var(--text-muted);
}

.empty-warning {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: var(--text-muted);
}

.empty-icon {
  margin-bottom: 12px;
}

@media (max-width: 768px) {
  .quick-actions-grid {
    grid-template-columns: 1fr;
  }

  .stat-value {
    font-size: 20px;
  }

  .chart-card {
    min-height: 300px;
  }
}
</style>
