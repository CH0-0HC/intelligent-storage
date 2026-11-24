/**
 * ECharts 按需注册入口（R21）。
 *
 * 【为什么需要它】
 * 原先各页面一律 `import * as echarts from 'echarts'` —— 这是 barrel 入口，
 * 会把 echarts **全部图表类型与全部组件**打进包。实测 `echarts-*.js` chunk
 * 达 **1,034.95 kB**（gzip 343 kB），是本项目前端最大的单块。
 *
 * 【按需注册的口径从哪来】
 * 不是拍脑袋列的，是对三个真正在用 echarts 的页面逐一 grep 出来的：
 *   · views/merchant/AIDashboard.vue —— bar / pie + category·value 轴 + graphic
 *   · views/merchant/Charts.vue      —— bar / line / pie / radar + graphic
 *   · views/merchant/Dashboard.vue   —— line + category·value 轴 + graphic
 * 另有 views/_archive/ 下 2 个已归档页面也 import 了 echarts（不参与构建，
 * 但为将来重新启用时不埋坑，一并改用本入口）。
 * ⇒ 需要的图表：Bar / Line / Pie / Radar（另备 Scatter，成本极低）
 * ⇒ 需要的组件：Title / Tooltip / Grid / Legend / Dataset / Transform /
 *                Polar / Radar / Graphic / MarkLine / Toolbox / DataZoom /
 *                AxisPointer / VisualMap
 * ⇒ 需要的能力：LabelLayout / UniversalTransition；渲染器：Canvas
 *
 * 【使用方式】
 *   import echarts from '@/utils/echarts'
 *   const chart = echarts.init(el)
 *   echarts.graphic.LinearGradient   // ✅ core 也导出 graphic
 *
 * 【注意】若新增页面用到未注册的图表/组件，运行时会报
 * 「Component xxx not exists」或图表空白 —— 届时把它补进下面的 use() 即可，
 * 不要退回 `from 'echarts'` 全量导入。
 */
import * as echarts from 'echarts/core'

import { BarChart, LineChart, PieChart, RadarChart, ScatterChart } from 'echarts/charts'

import {
  TitleComponent,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  DatasetComponent,
  TransformComponent,
  PolarComponent,
  RadarComponent,
  GraphicComponent,
  MarkLineComponent,
  MarkPointComponent,
  ToolboxComponent,
  DataZoomComponent,
  AxisPointerComponent,
  VisualMapComponent,
  AriaComponent
} from 'echarts/components'

import { LabelLayout, UniversalTransition } from 'echarts/features'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  RadarChart,
  ScatterChart,
  TitleComponent,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  DatasetComponent,
  TransformComponent,
  PolarComponent,
  RadarComponent,
  GraphicComponent,
  MarkLineComponent,
  MarkPointComponent,
  ToolboxComponent,
  DataZoomComponent,
  AxisPointerComponent,
  VisualMapComponent,
  AriaComponent,
  LabelLayout,
  UniversalTransition,
  CanvasRenderer
])

export default echarts
