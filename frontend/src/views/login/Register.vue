<template>
  <div class="login-container">
    <div class="flow-light"></div>
    <div class="flow-light"></div>
    <div class="flow-light"></div>
    
    <div class="login-box">
      <div class="login-header">
        <h1>{{ t('register.title') }}</h1>
        <p>{{ t('register.subtitle') }}</p>
      </div>
      
      <el-form ref="registerFormRef" :model="registerForm" :rules="rules" class="login-form">
        <el-form-item prop="username">
          <el-input 
            v-model="registerForm.username" 
            :placeholder="t('register.placeholder_username')"
            prefix-icon="User"
            size="large"
          />
        </el-form-item>
        
        <el-form-item prop="password">
          <el-input 
            v-model="registerForm.password" 
            type="password" 
            :placeholder="t('register.placeholder_password')"
            prefix-icon="Lock"
            size="large"
            show-password
          />
        </el-form-item>
        
        <el-form-item prop="confirmPassword">
          <el-input 
            v-model="registerForm.confirmPassword" 
            type="password" 
            :placeholder="t('register.placeholder_confirm_password')"
            prefix-icon="Lock"
            size="large"
            show-password
          />
        </el-form-item>
        
        <el-form-item prop="email">
          <el-input 
            v-model="registerForm.email" 
            :placeholder="t('register.placeholder_email')"
            prefix-icon="Message"
            size="large"
          />
        </el-form-item>
        
        <el-form-item prop="phone">
          <el-input 
            v-model="registerForm.phone" 
            :placeholder="t('register.placeholder_phone')"
            prefix-icon="Phone"
            size="large"
          />
        </el-form-item>
        
        <el-form-item>
          <el-select v-model="registerForm.role" :placeholder="t('register.role')" size="large">
            <el-option :label="t('register.user')" value="user" />
            <el-option :label="t('register.merchant')" value="merchant" />
          </el-select>
        </el-form-item>
        
        <el-form-item>
          <el-button 
            type="primary" 
            size="large" 
            :loading="loading" 
            style="width: 100%"
            @click="handleRegister"
          >
            {{ t('register.register') }}
          </el-button>
        </el-form-item>
        
        <div class="login-tips">
          <span>{{ t('register.has_account') }}</span>
          <a href="/login" class="link">{{ t('register.login') }}</a>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { register } from '@/api/auth'
import { useSettingsStore } from '@/stores/settings'
import { PHONE_PATTERN } from '@/utils/phonePattern'

const router = useRouter()
const { t, locale } = useI18n()
const settingsStore = useSettingsStore()
const registerFormRef = ref()
const loading = ref(false)

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
  phone: '',
  role: 'user'
})

const rules = {
  username: [{ required: true, message: t('register.username_required'), trigger: 'blur' }],
  // 【测试报告#1】原先 password 只有 required 一项：密码 "123" 直接注册成功并能登录下单，
  // 库里因此留下一批弱口令账号。这里补 ≥6 位，与「修改密码」那一侧的
  // （Password.vue: newPassword.length < 6）保持同一口径 —— 矛盾正是报告里用来反证的证据。
  password: [
    { required: true, message: t('register.password_required'), trigger: 'blur' },
    { min: 6, max: 64, message: t('password.too_short'), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: t('register.confirm_password_required'), trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ],
  email: [
    { required: true, message: t('register.email_required'), trigger: 'blur' },
    { type: 'email', message: t('register.email_format'), trigger: 'blur' }
  ],
  // 【测试报告#1】原先 phone 只有 required 一项：手机号 "abc" 照样通过，
  // 以 phone="abc" 入库并在管理员用户管理里可见。补格式校验（与后端 @Pattern 同口径）。
  phone: [
    { required: true, message: t('register.phone_required'), trigger: 'blur' },
    { pattern: PHONE_PATTERN, message: t('register.phone_invalid'), trigger: 'blur' }
  ]
}

function validateConfirmPassword(rule, value, callback) {
  if (value !== registerForm.password) {
    callback(new Error(t('register.password_not_match')))
  } else {
    callback()
  }
}

const handleRegister = async () => {
  if (!registerFormRef.value) return
  
  // 校验失败时 validate() 返回的 Promise 会 reject；追加 .catch 兜住，
  // 避免控制台出现 unhandled rejection（失败分支无需额外处理，表单已显示错误）。
  await registerFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const res = await register({
          username: registerForm.username,
          password: registerForm.password,
          email: registerForm.email,
          phone: registerForm.phone,
          role: registerForm.role
        })
        ElMessage.success(t('register.register_success'))
        router.push('/login')
      } catch (error) {
        console.error(error)
      } finally {
        loading.value = false
      }
    }
  }).catch(() => {})
}

onMounted(() => {
  locale.value = settingsStore.locale
  document.documentElement.lang = settingsStore.locale
  settingsStore.initTheme()
})
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  overflow: hidden;
  background: var(--gradient-bg);
  background-size: 300% 300%;
  animation: gradientShift 18s ease infinite;
  will-change: transform;
  transform: translateZ(0);
}

@keyframes gradientShift {
  0%   { background-position: 0% 50%; }
  50%  { background-position: 100% 50%; }
  100% { background-position: 0% 50%; }
}

.login-container::before,
.login-container::after {
  content: "";
  position: fixed;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  z-index: 0;
}

.login-container::before {
  width: 420px;
  height: 420px;
  background: rgba(255, 180, 200, 0.55);
  top: -120px;
  left: -80px;
}

.login-container::after {
  width: 480px;
  height: 480px;
  background: rgba(150, 200, 255, 0.55);
  bottom: -140px;
  right: -120px;
}

.dark .login-container::before {
  background: rgba(255, 100, 150, 0.3);
}

.dark .login-container::after {
  background: rgba(100, 150, 255, 0.3);
}

.flow-light {
  position: fixed;
  border-radius: 50%;
  filter: blur(60px);
  pointer-events: none;
  z-index: 0;
}

.flow-light:nth-child(1) {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, rgba(255, 146, 166, 0.45) 0%, transparent 60%);
  top: -100px;
  left: 10%;
  animation: flowMove1 15s ease-in-out infinite;
}

.flow-light:nth-child(2) {
  width: 450px;
  height: 450px;
  background: radial-gradient(circle, rgba(161, 196, 253, 0.45) 0%, transparent 60%);
  bottom: -80px;
  right: 5%;
  animation: flowMove2 18s ease-in-out infinite;
}

.flow-light:nth-child(3) {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(251, 194, 235, 0.4) 0%, transparent 60%);
  top: 40%;
  right: 20%;
  animation: flowMove3 12s ease-in-out infinite;
}

.dark .flow-light:nth-child(1) {
  background: radial-gradient(circle, rgba(255, 100, 150, 0.25) 0%, transparent 60%);
}

.dark .flow-light:nth-child(2) {
  background: radial-gradient(circle, rgba(100, 150, 255, 0.25) 0%, transparent 60%);
}

.dark .flow-light:nth-child(3) {
  background: radial-gradient(circle, rgba(200, 100, 200, 0.2) 0%, transparent 60%);
}

@keyframes flowMove1 {
  0%, 100% { transform: translate(0, 0) rotate(0deg); }
  33% { transform: translate(50px, 30px) rotate(120deg); }
  66% { transform: translate(20px, 60px) rotate(240deg); }
}

@keyframes flowMove2 {
  0%, 100% { transform: translate(0, 0) rotate(0deg); }
  33% { transform: translate(-40px, -20px) rotate(-120deg); }
  66% { transform: translate(-20px, -50px) rotate(-240deg); }
}

@keyframes flowMove3 {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(40px, -40px) scale(1.2); }
}

.login-box {
  width: 450px;
  padding: 48px;
  background: rgb(var(--glass-tint-rgb) / var(--glass-alpha-strong));
  background-image: var(--glass-sheen-gradient);
  /* 跟随滑块：不再写死 30px，改用 --glass-blur × 响应式缩放系数 */
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale))) saturate(calc(var(--glass-sat) * 1%));
  border: 1px solid var(--glass-border-thick);
  border-radius: 28px;
  box-shadow:
    0 20px 50px var(--shadow-color),
    inset 0 1px 1px rgba(255, 255, 255, 0.6);
  position: relative;
  z-index: 1;
  animation: loginFadeIn 0.5s cubic-bezier(0.4, 0, 0.2, 1) forwards;
  will-change: transform, opacity;
}

@keyframes loginFadeIn {
  0% {
    opacity: 0;
    transform: translateY(20px) scale(0.98);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-header h1 {
  font-size: 28px;
  color: var(--text-primary);
  margin-bottom: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.login-header p {
  font-size: 15px;
  color: var(--text-muted);
  line-height: 1.5;
}

.login-form {
  margin-top: 8px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-form :deep(.el-input__inner) {
  width: 100%;
  padding: 14px 18px;
  font-size: 17px;
  background: rgb(var(--glass-tint-rgb) / var(--glass-alpha-light));
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale)));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale)));
  border: 1px solid var(--glass-border);
  border-radius: 16px;
  outline: none;
  color: var(--text-primary);
  transition: all 0.3s cubic-bezier(0.25, 0.1, 0.25, 1);
  box-shadow: inset 0 1px 2px rgba(255, 255, 255, 0.6);
}

.login-form :deep(.el-input__inner:hover) {
  background: rgb(var(--glass-tint-strong-rgb) / calc(var(--glass-alpha-light) * 1.6));
}

.login-form :deep(.el-input__inner:focus) {
  background: rgb(var(--glass-tint-strong-rgb) / calc(var(--glass-alpha-light) * 1.8));
  border-color: var(--accent-color);
  box-shadow:
    0 0 0 4px var(--accent-light),
    inset 0 1px 2px rgba(255, 255, 255, 0.6);
}

.login-form :deep(.el-input__prefix) {
  color: var(--text-muted);
}

.login-form :deep(.el-input__placeholder) {
  color: var(--text-muted);
}

.login-form :deep(.el-select .el-input__inner) {
  background: rgb(var(--glass-tint-rgb) / var(--glass-alpha-light));
  border-color: var(--glass-border);
  color: var(--text-primary);
}

.login-form :deep(.el-option) {
  background: rgb(var(--glass-tint-strong-rgb) / calc(var(--glass-alpha) * 1.36 + 0.06));
  color: var(--text-primary);
}

.login-form :deep(.el-option:hover) {
  background: var(--accent-light);
}

.login-form :deep(.el-button--primary) {
  width: 100%;
  padding: 14px 18px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.3px;
  background: linear-gradient(135deg, var(--accent-color), rgba(88, 86, 214, 0.9));
  color: #ffffff;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 16px;
  box-shadow:
    0 6px 18px rgba(0, 122, 255, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.4);
  transition: all 0.25s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.login-form :deep(.el-button--primary:hover) {
  transform: translateY(-1px);
  box-shadow:
    0 10px 24px rgba(0, 122, 255, 0.4),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.login-form :deep(.el-button--primary:active) {
  transform: translateY(0);
}

.login-tips {
  text-align: center;
  font-size: 14px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 20px;
}

.login-tips .link {
  color: var(--accent-color);
  text-decoration: none;
  font-weight: 500;
}

.login-tips .link:hover {
  text-decoration: underline;
}
</style>
