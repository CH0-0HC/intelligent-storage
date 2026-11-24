<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import en from 'element-plus/dist/locale/en.mjs'

// Element Plus 组件语言包。
// 原实现是在 main.js 里用 `app.use(ElementPlus, { locale })` 做全局设置；
// 改为按需导入后不再整体 use(ElementPlus)，因此改用 <el-config-provider> 包裹
// （官方推荐做法），并跟随 vue-i18n 的 locale 做响应式切换。
// 注意：el-config-provider 本身会被 vite.config.js 里的 Components 插件自动按需导入。
const { locale } = useI18n()
const elementLocale = computed(() => (locale.value === 'en' ? en : zhCn))
</script>

<style>
#app {
  height: 100vh;
  overflow: hidden;
}
</style>
