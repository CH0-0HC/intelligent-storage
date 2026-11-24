import { createApp, watch } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 组件改为「自动按需导入」（见 vite.config.js 的 Components 插件），
// 因此这里不再 `import ElementPlus from 'element-plus'`，也不再 app.use(ElementPlus)。
// 样式保留全量 index.css，理由见 vite.config.js 注释。
import 'element-plus/dist/index.css'
import {
  ArrowDown, ArrowLeft, ArrowRight, Bell, Box, CaretLeft, CaretRight,
  ChatDotRound, Check, CircleCheck, CircleClose, Close, Cpu, DataAnalysis,
  Document, Download, Goods, Grid, HomeFilled, InfoFilled, List, Lock,
  MagicStick, Message, Money, Moon, Opportunity, Phone, PieChart, Plus,
  Refresh, Search, Service, Setting, Shop, ShoppingBag, ShoppingCart, Star,
  Sunny, Switch, Timer, User, UserFilled, Van, Wallet, Warning
} from '@element-plus/icons-vue'
import router, { syncDocumentTitle } from './router'
import App from './App.vue'
import i18n from './i18n'
import { useSettingsStore } from './stores/settings'
import 'nprogress/nprogress.css'
// 样式加载顺序有讲究：
//   1) global.scss   —— 基础重置、通用工具类、原有 .glass* 定义
//   2) theme.scss    —— 主题变量 + Element Plus 组件皮肤（含大量 !important）
//   3) glass.scss    —— 毛玻璃层，必须排在 theme.scss **之后**：
//      它要把 theme.scss 里写死的 `background: var(--bg-card) !important`
//      重新指向实时可调的玻璃底色，靠后的声明才能赢。
import './assets/styles/global.scss'
import './assets/styles/theme.scss'
import './assets/styles/glass.scss'
import { performanceMonitor } from './utils/performance'

const app = createApp(App)
const pinia = createPinia()

// 【全局错误兜底，必须留着】
// Vue 在**开发模式**下，对「没有 errorHandler 处理的组件错误」会重新抛出
// （内部 logError 的 throwInDev 分支）。后果不是「只坏一个组件」，而是
// **父组件的 patch 被整体中断** —— 2026-09-22 实测过的完整连锁：
//   AIAssistant.vue 模板引用了未定义的 status
//   → 渲染抛 TypeError
//   → Vue 重抛 → <ElContainer class="layout-container"> 的更新中断
//   → Element Plus 的 el-dropdown popper 再也拿不到定位更新，
//     停留在 position:absolute; left:0; top:0（下拉菜单跑到视口左上角）
//   → 且 el-header 里的用户名没渲染出来（header 的 patch 也被截断）
// 即：一个叶子组件的模板笔误，表现为「右上角下拉错位 + 头部显示不全」，
// 从现象完全看不出因果，排查成本极高。
// 注册 errorHandler 后 Vue 改为渲染注释占位符、父级 patch 继续，
// 故障被限制在出错组件内部；同时错误仍完整打到控制台，不掩盖问题。
app.config.errorHandler = (err, instance, info) => {
  // 不打成 Vue warn，而是明确的 error，便于「控制台无报错」这类验收一眼看出
  console.error(`[app.errorHandler] ${info}`, err)
}

// 图标全局注册（收敛版）。
// 为何仍需全局注册：Layout.vue 的菜单项与 AIDashboard.vue 的统计卡是用
// 「字符串图标名 + <component :is="item.icon" />」动态渲染的（如 icon: 'HomeFilled'），
// 构建期无法静态分析出这些用法，只能靠全局注册兜底。
// 原实现注册的是 @element-plus/icons-vue 的「全部」图标（数百个），现收敛到实际用到的 46 个。
// ⚠️ 这份清单必须覆盖**所有以字符串形式引用图标**的地方，否则渲染是**静默失败**的
//（不报错、只是一片空白，只有 Vue dev 模式给一条 "Failed to resolve component" 警告）：
//   · utils/menu.js 的 icon: 'Service'（「我的客服记录」+ 两条「客服工作台」，共 3 处）
//   · Layout.vue / Profile.vue 的 <el-avatar icon="UserFilled" />
//   · Login/Register 的 prefix-icon="Lock|Message|Phone"
//     —— el-avatar 与 el-input 内部都走 resolveDynamicComponent(字符串)，查的是全局注册表；
//     本项目没有配 IconsResolver（见 vite.config.js），所以只能靠这里兜底。
// 加新图标时：先 grep 字符串用法，再往下面两个清单同步加（import 与 globalIcons 必须一致）。
const globalIcons = {
  ArrowDown, ArrowLeft, ArrowRight, Bell, Box, CaretLeft, CaretRight,
  ChatDotRound, Check, CircleCheck, CircleClose, Close, Cpu, DataAnalysis,
  Document, Download, Goods, Grid, HomeFilled, InfoFilled, List, Lock,
  MagicStick, Message, Money, Moon, Opportunity, Phone, PieChart, Plus,
  Refresh, Search, Service, Setting, Shop, ShoppingBag, ShoppingCart, Star,
  Sunny, Switch, Timer, User, UserFilled, Van, Wallet, Warning
}
for (const [key, component] of Object.entries(globalIcons)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(i18n)

// 【2026-10-10 修复 · 切语言后浏览器标签标题不刷新】
// document.title 只在 router.beforeEach 里赋值，而切语言**不触发导航** ——
// 实测切到 English 后标签页仍显示「客服工作台 - 智能进销存系统」，
// 与界面其它英文文案脱节。这里监听 locale，用与导航完全相同的
// syncDocumentTitle() 重算一次，保证两条路径不会各自漂移。
watch(i18n.global.locale, () => {
  syncDocumentTitle()
})

// 【缺陷修复 · 暗色模式闪白(FOUC)】主题类必须在 mount 之前挂到 <html> 上，
// 否则首帧会以浅色渲染，随后再切成暗色，肉眼可见一次白屏闪烁。
const settingsStore = useSettingsStore()
settingsStore.initTheme()

// 【毛玻璃同理】把用户存的模糊半径/透明度在首帧前就写进 :root，
// 否则会先按默认玻璃渲染一帧、再跳到用户设置，拖动过滑块的用户会看到明显闪动。
// 内部还会做设备能力探测，明显带不动 backdrop-filter 的机器自动降级关闭。
settingsStore.initGlass()

app.mount('#app')

performanceMonitor.init()
