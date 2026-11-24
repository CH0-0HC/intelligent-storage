/**
 * AI 连接状态归一化 —— 前端消费 /ai/status 的唯一出口。
 *
 * 【为什么必须有这一层】
 * 后端 /ai/status 返回的是一份「链路诊断信息」：
 *   { provider, effective, connected, localConfigured, localModelAvailable,
 *     localBaseUrl, localModel, availableModels, message, ... }
 * 而早期页面直接绑定 status.connected —— 当时后端响应里根本没有这个字段，
 * 于是 status.connected === undefined → 模板里恒为假 →
 * 「Ollama 明明在跑，页面右上角却永远显示 AI 未连接，
 *   聊天输入框也一直是 disabled 状态点不了」。
 *
 * 现在后端已显式返回 connected，这里仍做一次容错归一化，原因是：
 *   1. 旧版本后端（或缓存中的旧构建）可能仍缺该字段，前端不应因此瘫痪；
 *   2. 模板里绝不希望出现 undefined 参与真假判断，统一在这里收敛成布尔值；
 *   3. 标签文案、提示语、来源说明集中一处，避免两个页面各写一套而逐渐漂移。
 */

export const AI_EFFECTIVE = {
  LOCAL: 'local',
  CLOUD: 'cloud',
  MODEL_MISSING: 'local-model-missing',
  DISABLED: 'disabled',
  NONE: 'none'
}

/** 配置来源的可读文案（与后端 AiConfigServiceImpl.scopeLabel 保持一致） */
const SCOPE_LABELS = {
  merchant: '商户自定义',
  global: '全局默认配置',
  default: '系统内置默认'
}

/**
 * 【i18n】把上面这些「中文常量」映射到 i18n 键。
 *
 * 为什么不在这里直接调 t()：
 *   本文件是纯工具函数（非组件），没有 i18n 上下文；
 *   且 normalizeAiStatus 的结果会被缓存进 ref，若在此处一次性翻译，
 *   切换语言后标签会停在旧语言，直到重新请求 /ai/status 才会刷新。
 * 因此这里只返回「键」，由组件在渲染时 t() 翻译。
 * label/detail 仍保留未翻译原文，供未接入 i18n 的调用方兜底。
 */
const SCOPE_LABEL_KEYS = {
  merchant: 'ai.scope_merchant',
  global: 'ai.scope_global',
  default: 'ai.scope_default'
}

const DETAIL_KEYS = {
  [AI_EFFECTIVE.LOCAL]: 'ai.detail_local',
  [AI_EFFECTIVE.CLOUD]: 'ai.detail_cloud',
  [AI_EFFECTIVE.MODEL_MISSING]: 'ai.detail_model_missing',
  [AI_EFFECTIVE.DISABLED]: 'ai.detail_disabled',
  [AI_EFFECTIVE.NONE]: 'ai.detail_none'
}

function fallbackDetailKey(effective) {
  return DETAIL_KEYS[effective] || DETAIL_KEYS[AI_EFFECTIVE.NONE]
}

function fallbackDetail(effective) {
  switch (effective) {
    case AI_EFFECTIVE.LOCAL:
      return '本地 AI 已连接'
    case AI_EFFECTIVE.CLOUD:
      return '本地 AI 不可用，已降级到云端模型'
    case AI_EFFECTIVE.MODEL_MISSING:
      return '已连上 Ollama 服务，但未找到所配置的模型，请先 ollama pull'
    case AI_EFFECTIVE.DISABLED:
      return '本地 AI 已被手动停用，可在「AI 设置」中重新启用'
    default:
      return '未检测到可用的 AI 服务，请在「AI 设置」中填写 Ollama 服务地址'
  }
}

/**
 * @param {object} raw 后端 /ai/status 的 data
 * @returns {{connected: boolean, effective: string, label: string, labelKey: string,
 *            detail: string, detailKey: string, baseUrl: string, model: string,
 *            models: string[], scopeLabel: string, scopeLabelKey: string,
 *            numCtx: number|undefined, tagType: string, raw: object}}
 *
 * 【i18n 约定】label / detail / scopeLabel 是「未翻译兜底原文」；
 * 组件展示时优先用对应的 *Key 走 t()，这样切换语言能即时生效。
 * detail 例外：后端下发的 message 是动态诊断文本，detailKey 为空，应直接展示 detail。
 */
export function normalizeAiStatus(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const effective = s.effective || AI_EFFECTIVE.NONE

  // 1) 优先采信后端显式的 connected；2) 缺失时按 effective 推断
  const connected = typeof s.connected === 'boolean'
    ? s.connected
    : (effective === AI_EFFECTIVE.LOCAL || effective === AI_EFFECTIVE.CLOUD)

  let label = 'AI 未连接'
  let labelKey = 'ai.status_disconnected'
  let tagType = 'danger'
  if (connected) {
    label = effective === AI_EFFECTIVE.CLOUD ? 'AI 已连接（云端）' : 'AI 已连接'
    labelKey = effective === AI_EFFECTIVE.CLOUD ? 'ai.status_connected_cloud' : 'ai.status_connected'
    tagType = 'success'
  } else if (effective === AI_EFFECTIVE.MODEL_MISSING || effective === AI_EFFECTIVE.DISABLED) {
    label = effective === AI_EFFECTIVE.DISABLED ? 'AI 已停用' : '模型未就绪'
    labelKey = effective === AI_EFFECTIVE.DISABLED ? 'ai.status_disabled' : 'ai.status_model_missing'
    tagType = 'warning'
  }

  // 后端 message 是动态诊断文本，保持原文不翻译；
  // 没有 message 时才回落到本地兜底文案（此时给出 detailKey 供组件翻译）。
  const hasBackendMessage = !!(s.message && String(s.message).trim())
  const detail = hasBackendMessage ? s.message : fallbackDetail(effective)
  const detailKey = hasBackendMessage ? '' : fallbackDetailKey(effective)

  return {
    connected,
    effective,
    label,
    labelKey,
    tagType,
    detail,
    detailKey,
    baseUrl: s.localBaseUrl || '',
    model: s.localModel || '',
    models: Array.isArray(s.availableModels) ? s.availableModels : [],
    scopeLabel: SCOPE_LABELS[s.configScope] || '',
    scopeLabelKey: SCOPE_LABEL_KEYS[s.configScope] || '',
    numCtx: s.numCtx,
    raw: s
  }
}
