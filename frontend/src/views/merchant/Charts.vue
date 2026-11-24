<template>
  <div class="charts-page">
    <el-row :gutter="20" class="stats-row">
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card class="stat-card">
          <div class="stat-item" :style="{ background: 'linear-gradient(135deg, #409eff 0%, #667eea 100%)' }">
            <div class="stat-info">
              <div class="stat-label">{{ t('charts.total_products') }}</div>
              <div class="stat-value">{{ stats.totalProducts }}</div>
            </div>
            <div class="stat-icon-box">
              <el-icon :size="32" color="#fff" />
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card class="stat-card">
          <div class="stat-item" :style="{ background: 'linear-gradient(135deg, #67c23a 0%, #85ce61 100%)' }">
            <div class="stat-info">
              <div class="stat-label">{{ t('charts.inventory_warning') }}</div>
              <div class="stat-value">{{ stats.warningProducts }}</div>
            </div>
            <div class="stat-icon-box">
              <el-icon :size="32" color="#fff" />
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card class="stat-card">
          <div class="stat-item" :style="{ background: 'linear-gradient(135deg, #e6a23c 0%, #f0c78a 100%)' }">
            <div class="stat-info">
              <div class="stat-label">{{ t('charts.total_stock') }}</div>
              <div class="stat-value">{{ stats.totalStock }}</div>
            </div>
            <div class="stat-icon-box">
              <el-icon :size="32" color="#fff" />
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card class="stat-card">
          <div class="stat-item" :style="{ background: 'linear-gradient(135deg, #f56c6c 0%, #f89898 100%)' }">
            <div class="stat-info">
              <div class="stat-label">{{ t('charts.product_categories') }}</div>
              <div class="stat-value">{{ stats.categoryCount }}</div>
            </div>
            <div class="stat-icon-box">
              <el-icon :size="32" color="#fff" />
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="12">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">{{ t('charts.sales_trend') }}</span>
              <el-tag type="primary" size="small">{{ t('charts.monthly_analysis') }}</el-tag>
            </div>
          </template>
          <div ref="salesTrendRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">{{ t('charts.category_sales_share') }}</span>
              <el-tag type="danger" size="small">{{ t('charts.distribution') }}</el-tag>
            </div>
          </template>
          <div ref="categoryPieRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="12">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">{{ t('charts.sales_top10') }}</span>
              <el-tag type="success" size="small">{{ t('charts.ranking') }}</el-tag>
            </div>
          </template>
          <div ref="productBarRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">{{ t('charts.inventory_distribution') }}</span>
              <el-tag type="warning" size="small">{{ t('charts.radar_chart') }}</el-tag>
            </div>
          </template>
          <div ref="inventoryRadarRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="24">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">{{ t('charts.warning_products_detail') }}</span>
              <el-tag type="danger" size="small">{{ t('charts.warning_count', { count: warningProducts.length }) }}</el-tag>
            </div>
          </template>
          <div class="warning-list" v-loading="loading">
            <el-table :data="warningProducts" stripe>
              <el-table-column type="index" :label="t('charts.index')" width="80" align="center">
                <template #default="scope">
                  <span style="color: #409eff; font-weight: 600;">{{ scope.$index + 1 }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="productName" :label="t('charts.product_name')" min-width="200" />
              <el-table-column prop="categoryName" :label="t('charts.category')" width="120" />
              <el-table-column prop="quantity" :label="t('charts.current_stock')" width="120" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.quantity <= 10 ? 'danger' : (row.quantity <= 30 ? 'warning' : 'success')" size="large">
                    {{ row.quantity }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="warningQuantity" :label="t('charts.warning_threshold')" width="120" align="center" />
              <el-table-column prop="price" :label="t('charts.price')" width="120" align="center">
                <template #default="{ row }">
                  <span style="color: #f56c6c; font-weight: 600;">¥{{ row.price }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('charts.status')" width="120" align="center">
                <template #default="{ row }">
                  <el-tag type="danger" size="small">{{ t('charts.insufficient_stock') }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('charts.operation')" width="150" align="center">
                <template #default="{ row }">
                  <el-button type="primary" link size="small" @click="goToInventory(row)">{{ t('charts.view_inventory') }}</el-button>
                  <el-button type="success" link size="small" @click="goToProduct(row)">{{ t('charts.view_product') }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import echarts from '@/utils/echarts'
import { getProductList } from '@/api/product'
import { getWarningInventories } from '@/api/inventory'
import { getDashboardStats } from '@/api/ai'

// 【R20】全页 i18n。图表配置里的 legend.data / series.name / yAxis.name / tooltip
// 会直接渲染到 canvas 上，属于「脚本内的可见文案」，必须同样走 t()，
// 否则切到 English 后卡片标题是英文、图例却还是中文。
// 注意：echarts 的枚举值（type: 'bar' / 'line' / 'pie' / 'radar' / 'category'…）
// 是 API 参数，绝不翻译。
const { t } = useI18n()

const router = useRouter()

const loading = ref(false)
const salesTrendRef = ref(null)
const categoryPieRef = ref(null)
const productBarRef = ref(null)
const inventoryRadarRef = ref(null)

// 【数据可视化修复·2026-09-23】售卖实况数据来自 /ai/dashboard 的真实订单聚合
// （月趋势 / 销量TOP10 / 品类销售占比），随每次进页实时计算，
// 替换原先写死的示例数组与「商品A/B/C…」等虚假数据。
const dashData = ref({ monthlyTrend: [], productRanking: [], categoryShare: [] })

const stats = ref({
  totalProducts: 0,
  warningProducts: 0,
  totalStock: 0,
  categoryCount: 5
})

const warningProducts = ref([])

let chartInstances = []

const initSalesTrendChart = (monthlyTrend) => {
  if (!salesTrendRef.value) return
  const chart = echarts.init(salesTrendRef.value)
  chartInstances.push(chart)

  // 【数据可视化修复·2026-09-23】绑定最近 6 个自然月的真实销售额与有效销售订单量
  // （已付款且未取消的订单）。原为写死的 [32000, 45000, …] / [320, 450, …] 示例数据。
  const trend = monthlyTrend || []
  const option = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { data: [t('charts.sales_amount'), t('charts.order_count')], top: 10 },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'category', data: trend.map(p => p.month) },
    yAxis: [
      { type: 'value', name: t('charts.sales_amount_yuan'), axisLine: { show: true, lineStyle: { color: '#409eff' } } },
      { type: 'value', name: t('charts.order_count'), axisLine: { show: true, lineStyle: { color: '#67c23a' } } }
    ],
    series: [
      {
        name: t('charts.sales_amount'),
        type: 'bar',
        data: trend.map(p => Number(p.amount)),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#409eff' },
            { offset: 1, color: '#667eea' }
          ]),
          borderRadius: [4, 4, 0, 0]
        },
        emphasis: {
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: '#667eea' },
              { offset: 1, color: '#409eff' }
            ])
          }
        }
      },
      {
        name: t('charts.order_count'),
        type: 'line',
        yAxisIndex: 1,
        data: trend.map(p => p.orders),
        itemStyle: { color: '#67c23a' },
        lineStyle: { width: 3 },
        symbolSize: 8,
        smooth: true
      }
    ]
  }
  chart.setOption(option)
}

const initCategoryPieChart = (categoryShare) => {
  if (!categoryPieRef.value) return
  const chart = echarts.init(categoryPieRef.value)
  chartInstances.push(chart)

  // 【数据可视化修复·2026-09-23】品类占比改为「品类**销售**占比」：
  // 按真实订单（已付款且未取消）的销售额聚合。原实现按商品数量统计且
  // 带写死示例（食品饮料 35 / 数码电子 25 …），与售卖情况无关。
  const finalData = (categoryShare || []).map(item => ({
    value: Number(item.amount),
    name: item.name
  }))

  const colors = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399']

  const option = {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: '5%', left: 'center', itemWidth: 12, itemHeight: 12 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
      label: { show: false, position: 'center' },
      emphasis: {
        label: { show: true, fontSize: 18, fontWeight: 'bold' }
      },
      labelLine: { show: false },
      data: finalData.map((item, index) => ({
        ...item,
        itemStyle: { color: colors[index % colors.length] }
      }))
    }]
  }
  chart.setOption(option)
}

const initProductBarChart = (productRanking) => {
  if (!productBarRef.value) return
  const chart = echarts.init(productBarRef.value)
  chartInstances.push(chart)

  // 【数据可视化修复·2026-09-23】销量TOP10 来自订单明细的真实售出件数
  // （后端按有效销售订单聚合）。原实现用 product.sales 静态字段（下单链路
  // 从不更新它）并兜底展示写死的 [1200, 980, …] / 商品A、商品B… 虚构数据。
  // 后端返回按销量降序，这里倒序以便 TOP1 显示在图表顶部。
  const top = [...(productRanking || [])].reverse()

  const productNames = top.map(p => p.name)
  const productSales = top.map(p => p.quantity)

  const option = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      data: productNames,
      axisLabel: { interval: 0, rotate: 0 }
    },
    series: [{
      type: 'bar',
      data: productSales,
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
          { offset: 0, color: '#667eea' },
          { offset: 1, color: '#409eff' }
        ]),
        borderRadius: [0, 4, 4, 0]
      }
    }]
  }
  chart.setOption(option)
}

const initInventoryRadarChart = (products) => {
  if (!inventoryRadarRef.value) return
  const chart = echarts.init(inventoryRadarRef.value)
  chartInstances.push(chart)

  const categoryStockMap = {}
  products.forEach(p => {
    const name = p.categoryName || t('charts.uncategorized')
    categoryStockMap[name] = (categoryStockMap[name] || 0) + (p.stock || 0)
  })

  const categories = Object.keys(categoryStockMap)
  const stockValues = categories.map(name => categoryStockMap[name])

  const indicators = categories.length > 0 ? categories : [t('charts.cat_beverage'), t('charts.cat_digital'), t('charts.cat_apparel'), t('charts.cat_home_use'), t('charts.cat_fresh')]
  const values = stockValues.length > 0 ? stockValues : [850, 620, 480, 350, 280]

  const option = {
    tooltip: {},
    radar: {
      indicator: indicators.map(name => ({ name: name, max: Math.max(...values, 100) }))
    },
    series: [{
      type: 'radar',
      data: [{
        value: values,
        name: t('charts.inventory_distribution'),
        areaStyle: {
          color: new echarts.graphic.RadialGradient(0.5, 0.5, 1, [
            { offset: 0, color: 'rgba(64, 158, 255, 0.8)' },
            { offset: 1, color: 'rgba(103, 126, 234, 0.8)' }
          ])
        },
        lineStyle: { color: '#409eff', width: 2 },
        itemStyle: { color: '#409eff' }
      }]
    }]
  }
  chart.setOption(option)
}

const loadAllData = async () => {
  try {
    const [productRes, inventoryRes, dashRes] = await Promise.all([
      getProductList({ page: 1, pageSize: 1000 }),
      getWarningInventories(),
      getDashboardStats()
    ])

    const products = productRes.data?.records || productRes.data || []
    const warnings = inventoryRes.data?.records || inventoryRes.data || []
    dashData.value = dashRes.data || dashData.value

    stats.value.totalProducts = products.length
    stats.value.warningProducts = warnings.length
    stats.value.totalStock = products.reduce((sum, p) => sum + (p.stock || 0), 0)

    const uniqueCategories = new Set(products.map(p => p.categoryName).filter(Boolean))
    stats.value.categoryCount = uniqueCategories.size

    warningProducts.value = warnings

    return products
  } catch (e) {
    console.error(t('charts.load_failed'), e)
    return []
  }
}

const handleResize = () => {
  chartInstances.forEach(chart => chart.resize())
}

const goToInventory = (row) => {
  router.push('/merchant/inventory')
}

const goToProduct = (row) => {
  router.push('/merchant/products')
}

onMounted(async () => {
  await nextTick()
  const products = await loadAllData()
  await nextTick()
  initSalesTrendChart(dashData.value.monthlyTrend)
  initCategoryPieChart(dashData.value.categoryShare)
  initProductBarChart(dashData.value.productRanking)
  initInventoryRadarChart(products)
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chartInstances.forEach(chart => chart.dispose())
  chartInstances = []
})
</script>

<style scoped>
.charts-page {
  padding: 20px;
  background: var(--bg-secondary);
  min-height: 100vh;
}

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  border: none;
  border-radius: 12px;
}

.stat-card :deep(.el-card__body) {
  padding: 0;
}

.stat-item {
  padding: 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-radius: 12px;
}

.stat-info {
  flex: 1;
}

.stat-label {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.8);
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #fff;
}

.stat-icon-box {
  width: 60px;
  height: 60px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chart-card {
  border-radius: 12px;
}

.chart-card :deep(.el-card__header) {
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.chart-container {
  height: 280px;
  padding: 10px;
}

.warning-list {
  padding: 10px;
}
</style>
