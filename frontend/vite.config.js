import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * 代理目标与端口允许用环境变量覆盖。
 *
 * 目的：能对着**临时实例**验证前端，而不打扰正在跑的 8080/5173。
 * 与后端验证脚本的 `-BaseUrl` 是同一套思路：
 *   VITE_API_TARGET=http://localhost:18080  VITE_PORT=5174  vite
 * 不设变量时（即日常 `npm run dev`）取值与历史行为完全一致。
 */
const API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8080'
const DEV_PORT = Number(process.env.VITE_PORT || 5173)

export default defineConfig({
  plugins: [
    vue(),
    // 模板里的 <el-button> 等自动按需导入（无需手写 import）。
    // importStyle: false —— 样式仍由 main.js 的全量 index.css 提供，原因：
    //   1) ElMessage / ElMessageBox / ElNotification / ElLoading 这类「函数式 API」
    //      的样式不会被本插件注入，按需模式下会「弹窗裸奔」；
    //   2) 按需注入的组件样式与项目自定义 scss（global.scss / theme.scss）
    //      存在加载顺序竞争，容易覆盖失效。
    // 真正决定 JS 体积的是下面 manualChunks 里「不给 element-plus 分组」那一条。
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
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern-compiler'
      }
    }
  },
  server: {
    port: DEV_PORT,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
        secure: false,
        rewrite: (path) => path.replace(/^\/api/, '/api')
      },
      '/uploads': {
        target: API_TARGET,
        changeOrigin: true,
        secure: false,
        rewrite: (path) => path.replace(/^\/uploads/, '/api/uploads')
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        // ⚠️【重要】不要给 element-plus 做分块分组！
        //
        // 早期配置里有一条 `if (id.includes('element-plus')) return 'element-plus'`，
        // 它会让**整个 Element Plus 被完整打包** —— 实测产物中出现了 ElCarousel /
        // ElDatePicker / ElTransfer 等项目中从未使用的组件，element-plus chunk 达 886 KB
        // （≈ 完整包体），按需导入形同虚设。
        //
        // 原因：该包的入口 `element-plus/es/index.mjs` 是「桶文件（barrel）」，re-export
        // 了全部组件。一旦对它做 manualChunks 分组，Rollup 会把整个桶连同全部 re-export
        // 一并保留，桶内成员无法再被 tree-shake。
        //
        // 移除该规则后：产物中未使用组件命中数归零，JS 总量 2300.4 → 1849.9 KB（-450.5 KB）。
        // 代价是 element-plus 代码分散进各路由 chunk（文件数 34 → 54），可接受。
        //
        // 注：echarts 当前 1011 KB 与分块规则无关 —— 项目用的是
        // `import * as echarts from 'echarts'`（全量导入），需改按需注册才能减小，属独立任务。
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
          if (id.includes('@element-plus/icons-vue')) return 'icons'
          if (id.includes('axios') || id.includes('nprogress')) return 'utils-vendor'
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('/pinia/') || id.includes('@vue/')) return 'vue-vendor'
        }
      }
    },
    // 单个 chunk 超过 500KB 时发出警告
    chunkSizeWarningLimit: 500
  }
})
