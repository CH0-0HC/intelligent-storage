<template>
  <div class="ai-analysis-container">
    <el-card>
      <template #header>
        <div class="header-content">
          <span>{{ t('ai.title') }}</span>
          <div class="header-actions">
            <el-tooltip :content="aiDetail" placement="bottom">
              <el-tag :type="ai.tagType" size="small">
                {{ aiLabel }}
              </el-tag>
            </el-tooltip>
            <el-button size="small" :loading="statusLoading" @click="loadStatus">
              <el-icon><Refresh /></el-icon>
              {{ t('ai.refresh_status') }}
            </el-button>
            <el-button size="small" type="primary" plain @click="settingsVisible = true">
              <el-icon><Setting /></el-icon>
              {{ t('ai.settings') }}
            </el-button>
          </div>
        </div>
      </template>

      <!-- 未连接时给出明确原因与一键入口，而不是让用户对着灰掉的输入框发呆 -->
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
      
      <!-- 【R20】快捷问题既是按钮文案、也是发给 AI 的提问内容，
           因此两边都用同一个 t() 键 —— 若只翻译显示而提问仍用中文，
           切换到 English 后会出现「按钮是英文、AI 收到的却是中文」的割裂。 -->
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
    </el-card>

    <AISettingsDialog v-model="settingsVisible" @saved="onSettingsSaved" />
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { User, ChatDotRound, Setting, Refresh } from '@element-plus/icons-vue'
import { chatWithAI, getAIStatus } from '@/api/ai'
import { normalizeAiStatus } from '@/utils/aiStatus'
import AISettingsDialog from '@/components/AISettingsDialog.vue'

// 【R20】全页 i18n。注意错误路径同样要走 t()：
// 只翻译「正常显示」而漏掉 ElMessage / console 的提示，
// 是切到 English 后最常见的残留（提示突然变中文）。
const { t } = useI18n()

// 【i18n】状态标签/明细走 key 翻译（跟随语言切换）；
// detail 若来自后端动态 message（detailKey 为空），则直接展示原文。
const aiLabel = computed(() => (ai.value.labelKey ? t(ai.value.labelKey) : ai.value.label))
const aiDetail = computed(() => (ai.value.detailKey ? t(ai.value.detailKey) : ai.value.detail))

const messages = ref([])
const inputMsg = ref('')
const loading = ref(false)
const statusLoading = ref(false)
const settingsVisible = ref(false)
const ai = ref(normalizeAiStatus(null))
const chatContainer = ref(null)

const loadStatus = async () => {
  statusLoading.value = true
  try {
    const res = await getAIStatus()
    // 【修复要点】此前这里是 status.value = res.data，而模板读的是
    // status.connected —— 后端响应里没有该字段，于是恒为 undefined（假），
    // 页面永远显示「AI 未连接」且输入框被 disabled。
    // 现统一经 normalizeAiStatus 归一化，字段缺失也会按 effective 正确推断。
    ai.value = normalizeAiStatus(res?.data)
  } catch (e) {
    console.error(t('ai.status_load_failed'), e)
    ai.value = normalizeAiStatus({
      effective: 'none',
      message: t('ai.status_load_failed_msg') + (e.message || t('ai.unknown_error'))
    })
  } finally {
    statusLoading.value = false
  }
}

const onSettingsSaved = () => {
  // 保存后立即回读状态，页面标签/提示即时反映新配置
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
.ai-analysis-container {
  padding: 20px;
  max-width: 1000px;
  margin: 0 auto;
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conn-alert {
  margin-bottom: 12px;
}

.chat-messages {
  height: 400px;
  overflow-y: auto;
  padding: 20px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 15px;
}

.quick-actions {
  margin-bottom: 15px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.message {
  display: flex;
  margin-bottom: 20px;
  max-width: 80%;
}

.message.user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.message.ai {
  margin-right: auto;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #409eff;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.message.user .message-avatar {
  background: #67c23a;
}

.message-content {
  margin: 0 12px;
}

.message-role {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.message.user .message-role {
  text-align: right;
}

.message-text {
  background: white;
  padding: 12px 16px;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
  white-space: pre-wrap;
  line-height: 1.6;
}

.message.user .message-text {
  background: #ecf5ff;
}

.thinking {
  display: flex;
  gap: 4px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #409eff;
  animation: bounce 1.4s infinite ease-in-out both;
}

.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; }
  40% { transform: scale(1); opacity: 1; }
}

.chat-input {
  display: flex;
  gap: 12px;
}

.chat-input .el-textarea {
  flex: 1;
}
</style>
