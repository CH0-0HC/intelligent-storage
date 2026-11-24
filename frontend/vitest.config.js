import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * 单元测试专用配置（与 vite.config.js 分离）。
 *
 * 【为什么单独一份、而不是复用 vite.config.js】
 * vite.config.js 里挂着构建期才需要的东西（manualChunks 分包规则等），
 * 对单测既无必要、又会拖慢启动。本文件只保留测试真正依赖的部分：
 * `@` 别名、jsdom 环境，以及下面这两个编译期插件。
 *
 * 【2026-10-09 变更：补上 vue 插件与按需导入】
 * 原先这里刻意不带 @vitejs/plugin-vue 与 unplugin-vue-components，
 * 因为被测对象只有 utils/*.js、stores/*.js 这类纯逻辑模块。
 * 现在新增了**页面级渲染测试**（首个：views/merchant/OrderManage.spec.js，
 * 用于锁住「商家端必须显示用户付款状态」这条纯视觉契约），
 * 少了这两个插件，.vue 文件连解析都过不去，`<el-table>` 之类的按需组件
 * 也不会被注入 —— 组件会静默渲染成空标签，测试反而给出假通过。
 * 代价是纯逻辑单测的启动会慢一点，远小于它换来的覆盖范围。
 *
 * 注意：本文件不影响 `npm run build` / `npm run dev` 的行为
 * （vite 只会读 vite.config.js，vitest 只会读本文件）。
 */
export default defineConfig({
  plugins: [
    vue(),
    // 与 vite.config.js 保持一致：importStyle: false，
    // 组件样式不由本插件注入（测试断言的是 DOM 文本，不依赖样式）。
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: false })]
    })
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.js', 'tests/**/*.spec.js']
  }
})
