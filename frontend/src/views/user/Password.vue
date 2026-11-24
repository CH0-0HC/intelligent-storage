<template>
  <div class="password-page">
    <el-card class="password-card">
      <template #header>
        <span class="page-title">{{ t('password.title') }}</span>
      </template>
      
      <div class="password-content">
        <el-form :model="passwordForm" label-width="120px" @submit.prevent="changePassword">
          <el-form-item :label="t('password.current_password')" prop="oldPassword">
            <el-input v-model="passwordForm.oldPassword" type="password" :placeholder="t('password.placeholder_current')" />
          </el-form-item>
          <el-form-item :label="t('password.new_password')" prop="newPassword">
            <el-input v-model="passwordForm.newPassword" type="password" :placeholder="t('password.placeholder_new')" />
          </el-form-item>
          <el-form-item :label="t('password.confirm_password')" prop="confirmPassword">
            <el-input v-model="passwordForm.confirmPassword" type="password" :placeholder="t('password.placeholder_confirm')" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="changePassword">{{ t('password.confirm_change') }}</el-button>
            <el-button @click="resetForm">{{ t('common.reset') }}</el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { updatePassword } from '@/api/auth'
import { clearAuth } from '@/utils/auth'

const { t } = useI18n()

const passwordForm = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const changing = ref(false)

const changePassword = async () => {
  if (!passwordForm.value.oldPassword) {
    ElMessage.error(t('password.current_required'))
    return
  }
  if (!passwordForm.value.newPassword) {
    ElMessage.error(t('password.new_required'))
    return
  }
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    ElMessage.error(t('password.not_match'))
    return
  }
  if (passwordForm.value.newPassword.length < 6) {
    ElMessage.error(t('password.too_short'))
    return
  }

  changing.value = true
  try {
    await updatePassword({
      oldPassword: passwordForm.value.oldPassword,
      newPassword: passwordForm.value.newPassword
    })
    ElMessage.success(t('password.change_success'))
    // 【收敛】改密后清登录态统一走 clearAuth()（清 pinia state + localStorage）
    clearAuth()
    setTimeout(() => {
      window.location.href = '/login'
    }, 1000)
  } catch (error) {
    ElMessage.error(error.message || t('password.change_failed'))
  } finally {
    changing.value = false
  }
}

const resetForm = () => {
  passwordForm.value = {
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  }
}
</script>

<style scoped>
.password-page {
  padding: 20px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.password-card {
  max-width: 500px;
  margin: 0 auto;
}

.password-content {
  padding-top: 20px;
}
</style>