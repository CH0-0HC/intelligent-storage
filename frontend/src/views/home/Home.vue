<template>
  <div class="home-page">
    <!-- 角色分流首页：同一路径 /home，按登录角色渲染完全不同的内容 -->
    <HomeMerchant v-if="isMerchant" />
    <HomeAdmin v-else-if="isAdmin" />
    <HomeUser v-else />
  </div>
</template>

<script setup>
/**
 * 首页角色分流入口
 *
 * 【改动背景】
 * 原 Home.vue 是一个 1900 行的单体组件，商家与用户共用同一套模板：
 * 顶部欢迎区、数据概览卡片、热门商品橱窗、AI 推荐位全部固定不变。
 * 虽然脚本里根据角色切换了 stats 数组和 notifications，
 * 但差异只有「几个数字卡片」，整体依然是同一张「商品橱窗」页面：
 *   - 商家登录后看到的仍是面向消费者的购物首页（热门商品、AI 推荐、加购物车）；
 *   - 用户也能看到「今日销售额 / 库存预警 / 采购需求」这类经营指标；
 *   - 且这些数字全是写死的演示值（12580、24、45、286…），与真实业务无关。
 *
 * 【区分目标】
 * 1. 让每个角色登录后第一眼看到的是「与自己职责匹配」的工作台，
 *    而不是一张通用页面；
 * 2. 商家首页聚焦「经营」——销售/订单/库存/商品/采购/AI 分析；
 *    用户首页聚焦「消费」——浏览/加购/下单/查订单；
 * 3. 管理员首页聚焦「平台治理」——用户与日志；
 * 4. 同时把写死的演示数字替换为后端真实接口数据。
 *
 * 【判断依据】
 * 角色标识 role 来自 localStorage.userInfo（登录接口响应整体落盘），
 * 其权威来源是服务端 Sa-Token 会话中的 userRole —— 由 AuthController.login()
 * 在登录成功时写入服务端会话，客户端无法伪造。
 * 前端仅据此决定「渲染哪套界面」，真正的数据权限由后端按会话再次强制校验。
 *
 * 判断逻辑统一收敛在 @/utils/auth，避免各页面各写一套字符串比较。
 */
import { computed } from 'vue'
import HomeMerchant from './HomeMerchant.vue'
import HomeUser from './HomeUser.vue'
import HomeAdmin from './HomeAdmin.vue'
import { isMerchant as isMerchantRole, isAdmin as isAdminRole } from '@/utils/auth'

const isMerchant = computed(() => isMerchantRole())
const isAdmin = computed(() => isAdminRole())
</script>

<style scoped>
.home-page {
  padding: 24px;
  max-width: 1600px;
  margin: 0 auto;
  min-height: calc(100vh - 80px);
}
</style>
