/**
 * 大陆手机号格式（1 开头、第二位 3-9、共 11 位）。
 *
 * 放在这里而不是各处内联，是因为同一口径要在三个地方保持一致：
 *   · 后端 OrderCreateDTO 的 @Pattern（下单收货电话）
 *   · 前端 utils/shippingRules.js（结算 / 立即购买弹窗）
 *   · 前端 views/login/Register.vue（注册手机号）
 * 任一处放宽都会重新造出「同系统两种口径」的老问题（测试报告#1 的原始症状）。
 */
export const PHONE_PATTERN = /^1[3-9]\d{9}$/
