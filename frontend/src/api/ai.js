import request from '@/utils/request'

// AI 对话
export function chatWithAI(message) {
  return request({
    url: '/ai/chat',
    method: 'post',
    data: { message }
  })
}

// AI 连接状态
export function getAIStatus() {
  return request({
    url: '/ai/status',
    method: 'get'
  })
}

// ==================== AI 连接配置（商家自助） ====================
// 保存后后端立即生效，无需重启服务。

// 读取当前生效的 AI 配置（含内置默认值、来源、连通性探测结果）
export function getAIConfig() {
  return request({
    url: '/ai/config',
    method: 'get'
  })
}

// 保存 AI 配置
export function saveAIConfig(data) {
  return request({
    url: '/ai/config',
    method: 'put',
    data
  })
}

// 恢复默认配置（清除本账号的自定义配置）
export function resetAIConfig() {
  return request({
    url: '/ai/config/reset',
    method: 'post'
  })
}

// 用尚未保存的参数试连一次
export function testAIConnection(data) {
  return request({
    url: '/ai/config/test',
    method: 'post',
    data,
    // 试连要等对方握手/超时，给足时间，避免用默认 30s 以外的隐式超时
    timeout: 20000
  })
}

// 拉取指定地址下已安装的模型列表
export function getAIModels(baseUrl) {
  return request({
    url: '/ai/config/models',
    method: 'get',
    params: baseUrl ? { baseUrl } : {},
    timeout: 20000
  })
}

// 库存预警分析
export function getInventoryWarning() {
  return request({
    url: '/ai/inventory/warning',
    method: 'get'
  })
}

// 经营报告生成
export function generateReport(period) {
  return request({
    url: '/ai/report',
    method: 'get',
    params: { period }
  })
}

// 销售预测（指定商品）
export function forecastSales(productId, days = 7) {
  return request({
    url: '/ai/sales/forecast',
    method: 'get',
    params: { productId, days }
  })
}

// 个性化商品推荐
export function getRecommendations(userId, limit = 10) {
  return request({
    url: '/ai/recommend',
    method: 'get',
    params: { userId, limit }
  })
}

// 仪表盘综合统计
export function getDashboardStats() {
  return request({
    url: '/ai/dashboard',
    method: 'get'
  })
}

// 获取商品列表（供AI分析选择）
export function getProductListForAI() {
  return request({
    url: '/ai/products',
    method: 'get'
  })
}
