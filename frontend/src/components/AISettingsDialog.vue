<template>
  <el-dialog
    v-model="visible"
    :title="t('ai.settings_dialog.title')"
    width="580px"
    top="6vh"
    append-to-body
    :close-on-click-modal="false"
    @open="load"
  >
    <el-alert
      v-if="tip"
      :type="tipType"
      :title="tip"
      :closable="false"
      show-icon
      class="probe-tip"
    />

    <el-form label-width="110px" label-position="right" class="ai-config-form">
      <el-form-item :label="t('ai.settings_dialog.enable_local')">
        <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        <span class="hint-inline">{{ t('ai.settings_dialog.enable_local_hint') }}</span>
      </el-form-item>

      <el-form-item :label="t('ai.settings_dialog.base_url')">
        <el-input
          v-model="form.baseUrl"
          placeholder="http://localhost:11434"
          clearable
        />
        <div class="hint">
          {{ t('ai.settings_dialog.base_url_hint_pre') }}
          <code>http://&lt;服务器IP&gt;:11434</code>{{ t('ai.settings_dialog.base_url_hint_post') }}
        </div>
      </el-form-item>

      <el-form-item :label="t('ai.settings_dialog.model')">
        <el-select
          v-model="form.model"
          filterable
          allow-create
          default-first-option
          :placeholder="t('ai.settings_dialog.model_placeholder')"
          style="width: 100%"
        >
          <el-option v-for="m in models" :key="m" :label="m" :value="m" />
        </el-select>
        <div class="hint">
          {{ t('ai.settings_dialog.model_installed') }}{{ models.length ? models.join('、') : t('ai.settings_dialog.model_none_detected') }}
        </div>
      </el-form-item>

      <el-form-item :label="t('ai.settings_dialog.num_ctx')">
        <el-input-number v-model="form.numCtx" :min="512" :max="131072" :step="1024" />
        <span class="hint-inline">{{ t('ai.settings_dialog.num_ctx_hint') }}</span>
      </el-form-item>

      <el-form-item :label="t('ai.settings_dialog.num_predict')">
        <el-input-number v-model="form.numPredict" :min="64" :max="8192" :step="64" />
        <span class="hint-inline">{{ t('ai.settings_dialog.num_predict_hint') }}</span>
      </el-form-item>

      <el-form-item :label="t('ai.settings_dialog.temperature')">
        <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
        <span class="hint-inline">{{ t('ai.settings_dialog.temperature_hint') }}</span>
      </el-form-item>

      <el-form-item :label="t('ai.settings_dialog.timeout')">
        <el-input-number v-model="form.timeoutSeconds" :min="5" :max="600" :step="5" />
        <span class="hint-inline">{{ t('ai.settings_dialog.timeout_hint') }}</span>
      </el-form-item>

      <el-form-item :label="t('ai.settings_dialog.current_scope')">
        <el-tag size="small" type="info">{{ scopeText || t('ai.settings_dialog.unknown') }}</el-tag>
        <span v-if="customized" class="hint-inline">{{ t('ai.settings_dialog.customized_hint') }}</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="dialog-footer">
        <div class="footer-left">
          <el-button
            text
            :disabled="!customized"
            :loading="resetting"
            @click="handleReset"
          >{{ t('ai.settings_dialog.reset_default') }}</el-button>
          <el-button text :loading="testing" @click="handleTest">{{ t('ai.settings_dialog.test_connection') }}</el-button>
          <el-button text :loading="loadingModels" @click="loadModels">{{ t('ai.settings_dialog.refresh_models') }}</el-button>
        </div>
        <div>
          <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" @click="handleSave">{{ t('ai.settings_dialog.save_and_apply') }}</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAIConfig, saveAIConfig, resetAIConfig, testAIConnection, getAIModels } from '@/api/ai'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'saved'])
const { t } = useI18n()

// 【i18n】scopeLabel 来自后端/AI设置接口，带 *Key 时优先走翻译
const scopeText = computed(() => (scopeLabelKey.value ? t(scopeLabelKey.value) : scopeLabel.value))

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const resetting = ref(false)
const loadingModels = ref(false)

const models = ref([])
const customized = ref(false)
const scopeLabel = ref('')
const scopeLabelKey = ref('')
const tip = ref('')
const tipType = ref('info')

const form = reactive({
  baseUrl: 'http://localhost:11434',
  model: '',
  numCtx: 4096,
  numPredict: 1024,
  temperature: 0.7,
  timeoutSeconds: 120,
  enabled: 1
})

/** 应用后端返回的配置视图（describeCurrent 的结构） */
const applyConfig = (data) => {
  if (!data) return
  form.baseUrl = data.baseUrl || 'http://localhost:11434'
  form.model = data.model || ''
  form.numCtx = data.numCtx ?? 4096
  form.numPredict = data.numPredict ?? 1024
  form.temperature = data.temperature ?? 0.7
  form.timeoutSeconds = data.timeoutSeconds ?? 120
  form.enabled = data.enabled ?? 1
  customized.value = !!data.customized
  scopeLabel.value = data.scopeLabel || ''
  scopeLabelKey.value = data.scopeLabelKey || ''
  models.value = Array.isArray(data.availableModels) ? data.availableModels : []
  tip.value = data.message || ''
  tipType.value = data.connected ? 'success' : 'warning'
}

const load = async () => {
  loading.value = true
  try {
    const res = await getAIConfig()
    applyConfig(res.data)
  } catch (e) {
    tip.value = e.message || t('ai.settings_dialog.load_config_failed')
    tipType.value = 'error'
  } finally {
    loading.value = false
  }
}

const loadModels = async () => {
  loadingModels.value = true
  try {
    const res = await getAIModels(form.baseUrl)
    models.value = Array.isArray(res.data) ? res.data : []
    if (!models.value.length) {
      ElMessage.warning(t('ai.settings_dialog.no_model_detected'))
    } else if (form.model && !models.value.includes(form.model)) {
      ElMessage.warning(t('ai.settings_dialog.model_not_at_address', { model: form.model }))
    } else {
      ElMessage.success(t('ai.settings_dialog.models_fetched', { count: models.value.length }))
    }
  } catch (e) {
    ElMessage.error(e.message || t('ai.settings_dialog.fetch_models_failed'))
  } finally {
    loadingModels.value = false
  }
}

const handleTest = async () => {
  testing.value = true
  try {
    const res = await testAIConnection({ baseUrl: form.baseUrl, model: form.model })
    const data = res.data || {}
    tipType.value = data.ok ? (data.modelAvailable ? 'success' : 'warning') : 'error'
    tip.value = data.message || (data.ok ? t('ai.settings_dialog.connect_success') : t('ai.settings_dialog.connect_failed'))
    if (data.ok && Array.isArray(data.models)) {
      models.value = data.models
    }
    if (data.ok && data.modelAvailable) {
      ElMessage.success(t('ai.settings_dialog.connect_success_model_ok'))
    } else if (data.ok) {
      ElMessage.warning(tip.value)
    } else {
      ElMessage.error(tip.value)
    }
  } catch (e) {
    tipType.value = 'error'
    tip.value = e.message || t('ai.settings_dialog.test_failed')
    ElMessage.error(tip.value)
  } finally {
    testing.value = false
  }
}

const handleSave = async () => {
  if (!form.baseUrl || !form.baseUrl.trim()) {
    ElMessage.warning(t('ai.settings_dialog.base_url_required'))
    return
  }
  if (!/^https?:\/\//i.test(form.baseUrl.trim())) {
    ElMessage.warning(t('ai.settings_dialog.base_url_scheme'))
    return
  }
  if (!form.model || !form.model.trim()) {
    ElMessage.warning(t('ai.settings_dialog.model_required'))
    return
  }
  saving.value = true
  try {
    const payload = {
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      numCtx: form.numCtx,
      numPredict: form.numPredict,
      temperature: form.temperature,
      timeoutSeconds: form.timeoutSeconds,
      enabled: form.enabled
    }
    const res = await saveAIConfig(payload)
    applyConfig(res.data)
    ElMessage.success(res.message || t('ai.settings_dialog.config_saved'))
    emit('saved', res.data)
    visible.value = false
  } catch (e) {
    ElMessage.error(e.message || t('ai.settings_dialog.save_failed'))
  } finally {
    saving.value = false
  }
}

const handleReset = async () => {
  try {
    await ElMessageBox.confirm(
      t('ai.settings_dialog.reset_confirm'),
      t('ai.settings_dialog.reset_default'),
      { type: 'warning' }
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    const res = await resetAIConfig()
    applyConfig(res.data)
    ElMessage.success(t('ai.settings_dialog.reset_done'))
    emit('saved', res.data)
  } catch (e) {
    ElMessage.error(e.message || t('ai.settings_dialog.reset_failed'))
  } finally {
    resetting.value = false
  }
}
</script>

<style scoped>
.probe-tip {
  margin-bottom: 14px;
}

.ai-config-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.hint {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted, #909399);
  margin-top: 4px;
  width: 100%;
}

.hint code {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--bg-button, rgba(128, 128, 128, 0.15));
}

.hint-inline {
  font-size: 12px;
  color: var(--text-muted, #909399);
  margin-left: 10px;
}

.dialog-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.footer-left {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
</style>
