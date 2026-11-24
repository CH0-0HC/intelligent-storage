<template>
  <el-container class="layout-container">
    <el-aside 
      :width="sidebarWidth" 
      class="sidebar"
      :class="{ 'sidebar-hidden': !sidebarVisible }"
    >
      <div class="logo">
        <h3>{{ t('layout.logo') }}</h3>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="sidebar-menu"
        background-color="transparent"
        text-color="rgba(255,255,255,0.8)"
        active-text-color="#409eff"
        router
      >
        <template v-for="item in menuList" :key="item.path">
          <el-menu-item :index="item.path" v-if="!item.children">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/home' }">{{ t('layout.sidebar.home') }}</el-breadcrumb-item>
            <el-breadcrumb-item v-if="currentTitle">
              {{ currentTitle }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        
        <div class="header-right">
          <div class="settings-buttons">
            <button 
              class="setting-btn"
              @click="handleLocaleToggle"
              :title="t('layout.language')"
            >
              <el-icon><Switch /></el-icon>
              <span>{{ localeLabel }}</span>
            </button>
            
            <button 
              class="setting-btn"
              @click="handleThemeToggle"
              :title="t('layout.theme')"
            >
              <el-icon><component :is="themeIcon" /></el-icon>
              <span>{{ themeLabel }}</span>
            </button>

            <!-- 毛玻璃（Liquid Glass）实时调节入口 -->
            <div class="appearance-anchor" ref="appearanceAnchorRef">
              <button
                class="setting-btn"
                :class="{ 'is-active': appearanceOpen }"
                @click="handleAppearanceToggle"
                :title="t('layout.appearance')"
                :aria-expanded="appearanceOpen ? 'true' : 'false'"
              >
                <el-icon><MagicStick /></el-icon>
                <span>{{ t('layout.appearance') }}</span>
              </button>

              <transition name="ap-pop">
                <!-- 包一层 div 承担定位与层级：
                     .el-icon 是建了层叠上下文的（z-index:auto + 有背景的祖先链），
                     若把 z-index 写在面板自身、而它又是按钮的兄弟，
                     面板会被压在后续内容之下。定位放到这层包装上最稳。 -->
                <div v-if="appearanceOpen" class="appearance-pop">
                  <GlassAppearancePanel />
                </div>
              </transition>
            </div>
          </div>
          
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-avatar :size="32" icon="UserFilled" />
              <span class="username">{{ userInfo.nickname || userInfo.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">{{ t('common.profile') }}</el-dropdown-item>
                <el-dropdown-item command="password">{{ t('common.password') }}</el-dropdown-item>
                <el-dropdown-item divided command="logout">{{ t('common.logout') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
    
    <div 
      class="sidebar-toggle"
      @click="toggleSidebar"
      @mousedown.prevent
    >
      <el-icon :size="20" class="toggle-icon">
        <component :is="sidebarVisible ? CaretLeft : CaretRight" />
      </el-icon>
    </div>
    
    <AIAssistant v-if="userInfo.role === 'merchant'" />
  </el-container>
</template>

<script setup>import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessageBox, ElMessage } from 'element-plus';
import { logout } from '@/api/auth';
import { useSettingsStore } from '@/stores/settings';
import { getUserInfo, clearAuth } from '@/utils/auth';
import { buildMenuList } from '@/utils/menu';
import AIAssistant from '@/components/AIAssistant.vue';
import GlassAppearancePanel from '@/components/GlassAppearancePanel.vue';
import { CaretLeft, CaretRight, ArrowDown, Switch, Sunny, Moon, MagicStick } from '@element-plus/icons-vue';

const router = useRouter()
const route = useRoute()
const { t, locale } = useI18n()
const settingsStore = useSettingsStore()

// ---- 毛玻璃调节面板 ----
const appearanceOpen = ref(false)
const appearanceAnchorRef = ref(null)

const handleAppearanceToggle = () => {
  appearanceOpen.value = !appearanceOpen.value
}

// 点击面板/按钮之外关闭。用 mousedown 而不是 click：
// 后者要等 mouseup 才触发，拖完滑块松手时若落点在面板外会误关闭。
const handleDocumentPointerDown = (event) => {
  if (!appearanceOpen.value) return
  const anchor = appearanceAnchorRef.value
  if (anchor && !anchor.contains(event.target)) {
    appearanceOpen.value = false
  }
}

// Esc 关闭，键盘用户也需要出口
const handleDocumentKeydown = (event) => {
  if (event.key === 'Escape' && appearanceOpen.value) {
    appearanceOpen.value = false
  }
}

const userInfo = ref({
  username: '',
  nickname: '',
  role: ''
})

/**
 * 【2026-10-10 修复 · 侧边栏默认收起】
 * 原实现是 `ref(false)` 且没有任何宽屏展开逻辑，于是**每次进入页面侧边栏都是收起的**：
 * 用户登录后看到一屏内容 + 左侧中部一个 1px 宽的小箭头，完全没有可见导航
 * （390/1440 两档实测 `aside width=1px`，菜单文本虽在 DOM 里但被 CSS 隐藏）。
 * 现改为「宽屏默认展开、窄屏默认收起」——与下面 handleViewportResize 的窄屏
 * 收起逻辑用同一个断点，保证两处判定一致。
 *
 * 注意 MOBILE_BREAKPOINT 必须声明在使用点之前：`<script setup>` 是线性执行的
 * `const` 有 TDZ，写在后面会让这里直接 ReferenceError。
 */
const MOBILE_BREAKPOINT = 768

const sidebarVisible = ref(window.innerWidth > MOBILE_BREAKPOINT)
const sidebarWidth = computed(() => sidebarVisible.value ? '200px' : '0px')

/**
 * 用户是否**手动**收起/展开过侧边栏。
 *
 * 【为什么要区分】窄屏下侧边栏必须收起（它是浮层抽屉，展开会盖住正文），
 * 但窄 → 宽放大时如果一律保持收起，那么「一开始在小窗口打开、后来拖大窗口」
 * 的用户依旧只看得到一个 1px 的小箭头，等于修复只覆盖了「直接在宽屏打开」这一种情况。
 * 反过来，用户自己点过收起后再缩放，我们又不该把侧边栏硬拽回来抢操作。
 * 所以：**用户动过就听用户的，没动过就按视口给默认值。**
 */
let userToggledSidebar = false

const activeMenu = computed(() => route.path)

// 【i18n】面包屑标题：优先按 titleKey 翻译（跟随语言切换），
// 否则退回路由里硬编码的 meta.title（仅作未知路由兜底）。
// 注意必须放在 computed 里，不能直接读 meta.title —— 后者是模块级常量，
// 切语言不会重新求值，面包屑会一直停在首次加载的语言。
const currentTitle = computed(() => {
  const key = route.meta && route.meta.titleKey
  return key ? t(key) : (route.meta && route.meta.title) || ''
})

const localeLabel = computed(() => {
  return locale.value === 'zh-CN' ? '中文' : 'English'
})

const themeLabel = computed(() => {
  const theme = settingsStore.theme
  return theme === 'light' ? t('layout.light') : t('layout.dark')
})

const themeIcon = computed(() => {
  const theme = settingsStore.theme
  return theme === 'light' ? Sunny : Moon
})

const toggleSidebar = () => {
  userToggledSidebar = true
  sidebarVisible.value = !sidebarVisible.value
}

const menuList = computed(() => buildMenuList(userInfo.value.role, t))

const handleCommand = async (command) => {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm(t('login.logout_confirm'), t('common.warning'), {
        type: 'warning'
      })
      await logout()
      // 【收敛】退出登录统一走 clearAuth()（清 pinia state + localStorage）
      clearAuth()
      router.push('/login')
      ElMessage.success(t('login.logout_success'))
    } catch (error) {
      if (error !== 'cancel') {
        clearAuth()
        router.push('/login')
      }
    }
  } else if (command === 'profile') {
    router.push('/user/profile')
  } else if (command === 'password') {
    router.push('/user/password')
  }
}

const handleLocaleToggle = () => {
  const newLocale = locale.value === 'zh-CN' ? 'en' : 'zh-CN'
  locale.value = newLocale
  settingsStore.setLocale(newLocale)
  document.documentElement.lang = newLocale
}

const handleThemeToggle = () => {
  settingsStore.toggleTheme()
}

watch(() => settingsStore.locale, (newLocale) => {
  locale.value = newLocale
  document.documentElement.lang = newLocale
})

onMounted(() => {
  // 【修复·P0-6】不再裸 JSON.parse(localStorage.userInfo)：
  // localStorage 被写坏 / 手工改成非法 JSON 时，解析异常会在 onMounted 抛出，
  // 直接让整个后台布局白屏（且报错栈指向渲染层，极难定位）。
  // 统一走 utils/auth 的 getUserInfo()：解析失败返回 {}，按未登录处理。
  userInfo.value = getUserInfo()
  
  locale.value = settingsStore.locale
  document.documentElement.lang = settingsStore.locale

  document.addEventListener('mousedown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeydown)
  window.addEventListener('resize', handleViewportResize)
  handleViewportResize()
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeydown)
  window.removeEventListener('resize', handleViewportResize)
})

/**
 * 【响应式】窄屏下侧边栏改为"浮层抽屉"，默认收起；
 * 从宽屏缩到窄屏时自动收起，避免浮层抽屉盖住正文。
 * 从窄屏放大回宽屏时：**用户没手动动过就自动展开**（见 userToggledSidebar 注释），
 * 动过则尊重用户的选择，避免抢操作。
 * MOBILE_BREAKPOINT 已上移到 sidebarVisible 之前声明（见那里）。
 */
let wasMobile = window.innerWidth <= MOBILE_BREAKPOINT

const handleViewportResize = () => {
  const isMobile = window.innerWidth <= MOBILE_BREAKPOINT
  if (isMobile && !wasMobile) {
    // 宽 → 窄：浮层抽屉会盖住正文，必须收起（系统行为，不计入"用户手动"）
    sidebarVisible.value = false
  } else if (!isMobile && wasMobile && !userToggledSidebar) {
    // 窄 → 宽：用户从没手动改过，就按默认值展开，别把导航一直藏着
    sidebarVisible.value = true
  }
  wasMobile = isMobile
  // 视口变化后，面板的定位可能落到屏幕外，直接收起更稳妥
  appearanceOpen.value = false
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
  position: relative;
  overflow: hidden;
  background: var(--gradient-bg);
  background-size: 200% 200%;
}

.layout-container::before,
.layout-container::after {
  content: "";
  position: fixed;
  border-radius: 50%;
  filter: blur(40px);
  pointer-events: none;
  z-index: 0;
}

.layout-container::before {
  width: 350px;
  height: 350px;
  background: rgba(255, 180, 200, 0.3);
  top: -60px;
  left: -30px;
}

.layout-container::after {
  width: 400px;
  height: 400px;
  background: rgba(150, 200, 255, 0.3);
  bottom: -60px;
  right: -50px;
}

.dark .layout-container::before {
  background: rgba(255, 100, 150, 0.15);
}

.dark .layout-container::after {
  background: rgba(100, 150, 255, 0.15);
}

.sidebar {
  /* 玻璃外壳：模糊/底色/高光全部走 glass.scss 的 token，
     因此拖动滑块时侧边栏会跟着实时变化。
     注意：本 <style scoped> 没有 lang="scss"（Vue 按纯 CSS 处理），
     不能写 @include/@mixin —— 必须展开成普通 CSS 属性。 */
  background-color: rgb(var(--glass-tint-strong-rgb) / var(--glass-alpha-base));
  background-image: var(--glass-sheen-gradient);
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  border-right: 1px solid var(--glass-border);
  overflow: hidden;
  transition: width 0.25s ease, background-color var(--glass-dur) var(--glass-ease);
  position: relative;
  flex-shrink: 0;
  box-shadow:
    8px 0 32px var(--glass-shadow-light),
    inset 0 1px 0 var(--glass-highlight-soft);
}

.sidebar.sidebar-hidden {
  width: 0 !important;
  padding: 0;
}

.sidebar-hidden .logo,
.sidebar-hidden .sidebar-menu {
  opacity: 0;
  pointer-events: none;
}

.sidebar-toggle {
  position: fixed;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 44px;
  height: 44px;
  border-radius: 0 var(--glass-radius-md) var(--glass-radius-md) 0;
  background: var(--glass-bg-solid);
  background-image: var(--glass-sheen-gradient);
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  border: 1px solid var(--glass-border);
  border-left: none;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-primary);
  box-shadow:
    4px 4px 20px var(--glass-shadow),
    inset 0 1px 0 var(--glass-highlight);
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  z-index: 100;
  touch-action: manipulation;
}

.sidebar-toggle:hover {
  background: var(--glass-bg-strong);
  transform: translateY(-50%) scale(1.08);
  box-shadow:
    6px 6px 28px var(--glass-shadow-hover),
    inset 0 1px 0 var(--glass-highlight-strong);
}

.sidebar-toggle:active {
  transform: translateY(-50%) scale(0.95);
}

.sidebar:not(.sidebar-hidden) + .el-container + .sidebar-toggle {
  left: 200px;
}

.logo,
.sidebar-menu {
  transition: opacity 0.25s ease;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 与顶栏同一档玻璃，视觉上连贯成一条（纯 CSS 展开，原因同 .sidebar） */
  background-color: rgb(var(--glass-tint-strong-rgb) / var(--glass-alpha-solid));
  background-image: var(--glass-sheen-gradient);
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  border-bottom: 1px solid var(--glass-border);
}

.logo h3 {
  color: var(--text-primary);
  font-size: 17px;
  margin: 0;
  font-weight: 600;
}

.sidebar-menu {
  border-right: none;
  height: calc(100vh - 60px);
  background: transparent;
  padding: 12px 0;
}

.sidebar-menu :deep(.el-menu) {
  background: transparent;
  border: none;
}

.sidebar-menu :deep(.el-menu-item) {
  color: var(--text-secondary);
  margin: 4px 8px;
  border-radius: 10px;
  transition: background 0.2s ease;
}

.sidebar-menu :deep(.el-menu-item:hover) {
  background: var(--accent-light);
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background: var(--accent-light);
  color: var(--accent-color);
}

.header {
  /* 顶栏玻璃（纯 CSS 展开，原因同 .sidebar） */
  background-color: rgb(var(--glass-tint-strong-rgb) / var(--glass-alpha-solid));
  background-image: var(--glass-sheen-gradient);
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  box-shadow:
    0 4px 24px var(--glass-shadow),
    inset 0 1px 0 var(--glass-highlight);
  border-bottom: 1px solid var(--glass-border);
  height: 60px;
  /* 顶栏在正文之上，需要盖住滚动内容；也保证调节面板层级关系正确 */
  position: relative;
  z-index: 20;
}

.header-left {
  display: flex;
  align-items: center;
}

.header-left :deep(.el-breadcrumb__item a) {
  color: var(--text-muted) !important;
}

.header-left :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--text-primary) !important;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.settings-buttons {
  display: flex;
  gap: 8px;
}

.setting-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 10px;
  background: var(--bg-button);
  border: 1px solid var(--border-light);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.setting-btn:hover {
  background: var(--accent-hover);
  color: var(--accent-color);
}

/* 毛玻璃调节入口：打开时按钮保持高亮，明确"当前面板属于它" */
.setting-btn.is-active {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: #fff;
}

/* 面板定位锚点：不设 position:relative，
   否则需要自己算 absolute 的定位与裁切；这里让 .appearance-pop 用 fixed 贴住视口右上角，
   既不受 .header 的 overflow 影响，也不会在窄屏溢出。 */
.appearance-anchor {
  display: flex;
}

.appearance-pop {
  position: fixed;
  top: 68px;
  right: 16px;
  /* 高于顶栏(z-index:20)与 Element Plus 弹层(2000 档)，保证面板永远在最前 */
  z-index: 2600;
}

/* 弹出过渡：轻微缩放 + 位移，呼应"液态玻璃"的顺滑感 */
.ap-pop-enter-active,
.ap-pop-leave-active {
  transition: opacity 0.22s var(--glass-ease), transform 0.22s var(--glass-ease);
}

.ap-pop-enter-from,
.ap-pop-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.97);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 10px;
  background: var(--bg-button);
  border: 1px solid var(--border-light);
  transition: background 0.2s ease;
}

.user-info:hover {
  background: var(--accent-hover);
}

.username {
  font-size: 14px;
  color: var(--text-primary);
  font-weight: 500;
}

.main-content {
  /* 正文区是"最后一道玻璃"：它负责把背衬光斑糊成柔和的底色，
     卡片再以半透明玻璃叠在其上，形成有层次的通透感。
     注意全项目只有这一处负责主内容区模糊 —— 卡片一律不加 backdrop-filter，
     避免"模糊套模糊"导致滚动掉帧。 */
  background-color: rgb(var(--glass-tint-rgb) / var(--glass-alpha-veil));
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  padding: 20px;
  overflow-y: auto;
  height: calc(100vh - 60px);
  /* 提示浏览器把滚动内容与模糊隔离，减少滚动时的重绘范围 */
  contain: paint;
}

.main-content::-webkit-scrollbar {
  width: 6px;
}

.main-content::-webkit-scrollbar-track {
  background: var(--scrollbar-track);
  border-radius: 3px;
}

.main-content::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: 3px;
}

.main-content::-webkit-scrollbar-thumb:hover {
  background: var(--scrollbar-thumb-hover);
}

/* ==========================================================================
 *  响应式：不同屏幕尺寸下保证布局不破、毛玻璃观感一致
 * ========================================================================== */

/* 中等屏幕（平板/小笔记本）：收紧间距，按钮只留图标，避免顶栏拥挤换行 */
@media (max-width: 1200px) {
  .header {
    padding: 0 14px;
  }

  .setting-btn span {
    display: none;
  }

  .setting-btn {
    padding: 6px 8px;
  }
}

/* 窄屏（手机/竖屏平板）：侧边栏改为浮层抽屉，不再挤压正文 */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    height: 100vh;
    z-index: 1100;
    /* 抽屉贴在页面上，需要比常态更实的底色才能盖住正文 */
    background-color: rgb(var(--glass-tint-strong-rgb) / calc(var(--glass-alpha) * 1.35 + 0.1));
  }

  .sidebar:not(.sidebar-hidden) {
    width: 200px !important;
  }

  /* 抽屉展开时压暗正文，让"浮层"关系明确；
     同时避免用户误以为正文还能点 */
  .sidebar:not(.sidebar-hidden)::after {
    content: '';
    position: fixed;
    inset: 0 0 0 200px;
    background: rgba(0, 0, 0, 0.28);
    pointer-events: none;
  }

  /* 窄屏下切换按钮跟随抽屉边缘，不随侧栏宽度变化而丢失 */
  .sidebar:not(.sidebar-hidden) + .el-container + .sidebar-toggle {
    left: 200px;
  }

  .header {
    padding: 0 10px;
  }

  /* 【2026-10-10 · 触达尺寸】`.setting-btn` 默认约 27px（padding 6px + 13px 字，
     ≤1200px 还会只剩图标），窄屏单手很难点中。提到 40px；
     顶栏 .el-header 高 60px，放得下且上下留白仍充足。
     只在窄屏生效，桌面端观感不变。 */
  .setting-btn {
    min-height: 40px;
  }

  .header-left {
    min-width: 0;
    flex: 1;
    overflow: hidden;
  }

  .header-left :deep(.el-breadcrumb) {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .username {
    /* 用户名在窄屏隐藏，但下拉箭头保留，功能不丢 */
    display: none;
  }

  .main-content {
    padding: 12px;
    /* 100dvh 在移动端浏览器地址栏收起/展开时更稳定，避免底部被裁 */
    height: calc(100dvh - 60px);
  }

  .user-info {
    padding: 6px 8px;
  }
}

/* 超窄屏：面板与顶栏进一步压缩 */
@media (max-width: 480px) {
  .header-right {
    gap: 6px;
  }

  .settings-buttons {
    gap: 4px;
  }

  .appearance-pop {
    top: 62px;
    right: 8px;
    left: 8px;
  }

  .main-content {
    padding: 8px;
  }
}
</style>
