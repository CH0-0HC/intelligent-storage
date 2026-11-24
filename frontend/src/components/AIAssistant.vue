<template>
  <div class="ai-assistant">
    <!-- 悬浮球 -->
    <div class="floating-button" @click="toggleChat">
      <el-icon :size="32">
        <ChatDotRound v-if="!dialogVisible" />
        <Close v-else />
      </el-icon>
      <div v-if="ai.connected" class="status-dot"></div>
    </div>

    <!-- 聊天对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="t('ai.title')"
      width="500px"
      :close-on-click-modal="false"
      class="ai-chat-dialog"
    >
      <div class="chat-header">
        <el-tooltip :content="aiDetail" placement="bottom">
          <el-tag :type="ai.tagType" size="small">
            {{ aiLabel }}
          </el-tag>
        </el-tooltip>
        <el-button size="small" link :loading="statusLoading" @click="loadStatus">
          <el-icon><Refresh /></el-icon>
        </el-button>
        <el-button size="small" link @click="settingsVisible = true">
          <el-icon><Setting /></el-icon>
          {{ t('ai.settings') }}
        </el-button>
      </div>

      <!-- 未连接时说明原因并提供一键进入设置，避免用户以为「输入框坏了」 -->
      <el-alert
        v-if="!ai.connected"
        type="warning"
        :closable="false"
        show-icon
        class="conn-alert"
      >
        <template #title>
          <span>{{ aiDetail }}</span>
          <el-button link type="primary" size="small" @click="settingsVisible = true">
            {{ t('ai.go_settings') }}
          </el-button>
        </template>
      </el-alert>
      
      <div class="chat-messages" ref="chatContainer">
        <div 
          v-for="(msg, index) in messages" 
          :key="index" 
          :class="['message', msg.type]"
        >
          <div class="message-avatar">
            <el-icon v-if="msg.type === 'user'">
              <User />
            </el-icon>
            <el-icon v-else>
              <ChatDotRound />
            </el-icon>
          </div>
          <div class="message-content">
            <div class="message-role">{{ msg.type === 'user' ? t('ai.role_me') : t('ai.role_ai') }}</div>
            <div class="message-text">{{ msg.content }}</div>
          </div>
        </div>
        <div v-if="loading" class="message ai">
          <div class="message-avatar">
            <el-icon>
              <ChatDotRound />
            </el-icon>
          </div>
          <div class="message-content">
            <div class="message-role">{{ t('ai.role_ai') }}</div>
            <div class="message-text thinking">
              <span class="dot"></span>
              <span class="dot"></span>
              <span class="dot"></span>
            </div>
          </div>
        </div>
      </div>
      
      <div class="quick-actions">
        <el-button size="small" @click="askQuestion(t('ai.quick_inventory'))">{{ t('ai.quick_inventory') }}</el-button>
        <el-button size="small" @click="askQuestion(t('ai.quick_forecast'))">{{ t('ai.quick_forecast') }}</el-button>
        <el-button size="small" @click="askQuestion(t('ai.quick_report'))">{{ t('ai.quick_report') }}</el-button>
        <el-button size="small" @click="askQuestion(t('ai.quick_recommend'))">{{ t('ai.quick_recommend') }}</el-button>
      </div>
      
      <div class="chat-input">
        <el-input
          v-model="inputMsg"
          type="textarea"
          :rows="2"
          :placeholder="t('ai.input_placeholder')"
          @keyup.enter.ctrl="sendMessage"
        />
        <el-button 
          type="primary" 
          @click="sendMessage" 
          :loading="loading"
          :disabled="!inputMsg.trim()"
        >
          {{ t('ai.send') }}
        </el-button>
      </div>
    </el-dialog>

    <AISettingsDialog v-model="settingsVisible" @saved="onSettingsSaved" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { User, ChatDotRound, Close, Setting, Refresh } from '@element-plus/icons-vue'
import { chatWithAI, getAIStatus } from '@/api/ai'
import { normalizeAiStatus } from '@/utils/aiStatus'

const { t } = useI18n()

// 【i18n】状态标签/明细走 key 翻译（跟随语言切换）；
// detail 若来自后端动态 message（detailKey 为空），则直接展示原文。
const aiLabel = computed(() => (ai.value.labelKey ? t(ai.value.labelKey) : ai.value.label))
const aiDetail = computed(() => (ai.value.detailKey ? t(ai.value.detailKey) : ai.value.detail))
import AISettingsDialog from '@/components/AISettingsDialog.vue'

const dialogVisible = ref(false)
const messages = ref([])
const inputMsg = ref('')
const loading = ref(false)
const statusLoading = ref(false)
const settingsVisible = ref(false)
const ai = ref(normalizeAiStatus(null))
const chatContainer = ref(null)

const toggleChat = () => {
  dialogVisible.value = !dialogVisible.value
}

const loadStatus = async () => {
  statusLoading.value = true
  try {
    const res = await getAIStatus()
    // 【修复要点】此前是 status.value = res.data，而模板读的是 status.connected，
    // 后端响应里并没有该字段 → 恒为 undefined（假）→ 悬浮球没有绿点、
    // 标签永远「AI未连接」、输入框被 disabled。现统一经归一化处理。
    ai.value = normalizeAiStatus(res?.data)
  } catch (e) {
    console.error('获取AI状态失败', e)
    ai.value = normalizeAiStatus({ effective: 'none', message: t('ai.status_load_failed_msg') + (e.message || t('ai.unknown_error')) })
  } finally {
    statusLoading.value = false
  }
}

const onSettingsSaved = () => {
  loadStatus()
}

const sendMessage = async () => {
  if (loading.value || !inputMsg.value.trim()) return
  
  const userMsg = inputMsg.value.trim()
  messages.value.push({
    type: 'user',
    content: userMsg
  })
  inputMsg.value = ''
  loading.value = true
  
  await scrollToBottom()
  
  try {
    const res = await chatWithAI(userMsg)
    messages.value.push({
      type: 'ai',
      content: res.data
    })
  } catch (e) {
    ElMessage.error(e.message || t('ai.send_failed'))
    messages.value.push({
      type: 'ai',
      content: t('ai.send_failed_reply')
    })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

const askQuestion = (question) => {
  inputMsg.value = question
  sendMessage()
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  }
}

onMounted(() => {
  loadStatus()
  messages.value.push({
    type: 'ai',
    content: t('ai.welcome')
  })
})
</script>

<style scoped>
.ai-assistant {
  position: fixed;
  bottom: 30px;
  right: 30px;
  z-index: 9999;
}

.floating-button {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  background: rgb(var(--glass-tint-rgb) / var(--glass-alpha-base));
  background-image: var(--glass-sheen-gradient);
  /* 悬浮按钮是单一固定元素，保留真模糊并跟随滑块 */
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale)));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale)));
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-primary);
  cursor: pointer;
  box-shadow:
    0 10px 30px var(--glass-shadow),
    inset 0 1px 1px var(--glass-highlight);
  transition: transform 0.25s ease, box-shadow 0.25s ease;
  position: relative;
  border: 1px solid var(--glass-border-thick);
}

.floating-button:hover {
  transform: scale(1.08);
  box-shadow:
    0 15px 40px var(--glass-shadow-hover),
    inset 0 1px 1px var(--glass-highlight);
  background: rgb(var(--glass-tint-strong-rgb) / calc(var(--glass-alpha-base) * 1.3));
}

.status-dot {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: rgba(52, 199, 89, 0.9);
  border: 2px solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 8px rgba(52, 199, 89, 0.6);
}

.ai-chat-dialog :deep(.el-dialog) {
  border-radius: 24px;
  background: rgb(var(--glass-tint-strong-rgb) / calc(var(--glass-alpha) * 1.42 + 0.06));
  background-image: var(--glass-sheen-gradient);
  /* 不再叠加 backdrop-filter：弹窗带 open/close 动画，
     动画期间每帧重新采样整屏背景是"打开弹窗卡一下"的主因，
     玻璃观感交由较实的底色 + 高光边框承担。 */
  border: 1px solid var(--glass-border-thick);
  box-shadow:
    0 20px 60px var(--glass-shadow-strong),
    inset 0 1px 1px var(--glass-highlight);
}

.ai-chat-dialog :deep(.el-dialog__header) {
  background: rgb(var(--glass-tint-rgb) / var(--glass-alpha-veil));
  border-bottom: 1px solid var(--glass-border-light);
}

.ai-chat-dialog :deep(.el-dialog__title) {
  color: var(--text-primary);
  font-weight: 600;
}

.ai-chat-dialog :deep(.el-dialog__body) {
  background: rgba(255, 255, 255, 0.3);
}

.chat-header {
  margin-bottom: 15px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.chat-header :deep(.el-button) {
  color: rgba(60, 60, 70, 0.75);
}

.conn-alert {
  margin-bottom: 12px;
}

.chat-header :deep(.el-tag) {
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.7);
  color: rgba(60, 60, 70, 0.85);
}

.chat-header :deep(.el-tag--success) {
  background: rgba(52, 199, 89, 0.15);
  border-color: rgba(52, 199, 89, 0.3);
  color: rgba(52, 199, 89, 0.9);
}

.chat-header :deep(.el-tag--danger) {
  background: rgba(255, 59, 48, 0.15);
  border-color: rgba(255, 59, 48, 0.3);
  color: rgba(255, 59, 48, 0.9);
}

.chat-messages {
  height: 350px;
  overflow-y: auto;
  padding: 15px;
  background: rgba(255, 255, 255, 0.35);
  border-radius: 16px;
  margin-bottom: 15px;
  border: 1px solid rgba(255, 255, 255, 0.4);
}

.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.2);
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: rgba(100, 100, 120, 0.3);
  border-radius: 3px;
}

.quick-actions {
  margin-bottom: 15px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.quick-actions :deep(.el-button) {
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.7);
  color: rgba(60, 60, 70, 0.85);
}

.quick-actions :deep(.el-button:hover) {
  background: rgba(255, 255, 255, 0.8);
}

.message {
  display: flex;
  margin-bottom: 16px;
  max-width: 85%;
}

.message.user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.message.ai {
  margin-right: auto;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(0, 122, 255, 0.15);
  /* 去掉 backdrop-filter：头像只有 36px 且每条消息一个，
     在其上做背景采样收益极小而开销可观（消息越多越明显）。 */
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(0, 122, 255, 0.9);
  flex-shrink: 0;
  border: 1px solid rgba(0, 122, 255, 0.25);
}

.message.user .message-avatar {
  background: rgba(52, 199, 89, 0.15);
  color: rgba(52, 199, 89, 0.9);
  border-color: rgba(52, 199, 89, 0.25);
}

.message-content {
  margin: 0 10px;
}

.message-role {
  font-size: 11px;
  color: rgba(60, 60, 70, 0.55);
  margin-bottom: 4px;
}

.message.user .message-role {
  text-align: right;
}

.message-text {
  background: rgb(var(--glass-tint-rgb) / var(--glass-alpha-solid));
  /* 消息气泡数量随对话增长且位于滚动区内，去掉 backdrop-filter，
     改为玻璃底色 + 高光，滚动时不再逐个重新采样背景。 */
  background-image: var(--glass-sheen-gradient);
  padding: 12px 16px;
  border-radius: 16px;
  box-shadow:
    0 8px 24px var(--glass-shadow),
    inset 0 1px 1px var(--glass-highlight);
  white-space: pre-wrap;
  line-height: 1.6;
  font-size: 14px;
  color: var(--text-primary);
  border: 1px solid var(--glass-border);
}

.message.user .message-text {
  background: rgba(0, 122, 255, 0.25);
  background-image: none;
  border-color: rgba(0, 122, 255, 0.35);
  color: var(--text-primary);
}

.thinking {
  display: flex;
  gap: 6px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(0, 122, 255, 0.85);
  animation: bounce 1.4s infinite ease-in-out;
}

.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.7); opacity: 0.5; }
  40% { transform: scale(1); opacity: 1; }
}

.chat-input {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.chat-input .el-textarea {
  flex: 1;
}

.chat-input :deep(.el-textarea__inner) {
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.7);
  color: rgba(60, 60, 70, 0.9);
  border-radius: 12px;
}

.chat-input :deep(.el-textarea__placeholder) {
  color: rgba(100, 100, 120, 0.45);
}

.chat-input :deep(.el-button--primary) {
  background: rgba(0, 122, 255, 0.9);
  border: 1px solid rgba(0, 122, 255, 0.95);
  color: #ffffff;
  border-radius: 12px;
}

.chat-input :deep(.el-button--primary:hover) {
  background: rgba(0, 122, 255, 1);
}
</style>
