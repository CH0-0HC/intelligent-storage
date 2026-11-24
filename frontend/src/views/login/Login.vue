<template>
  <div class="login-container">
    <div class="flow-light"></div>
    <div class="flow-light"></div>
    <div class="flow-light"></div>
    
    <div class="login-box">
      <div class="login-header">
        <h1>{{ t('login.title') }}</h1>
        <p>{{ t('login.subtitle') }}</p>
      </div>
      
      <el-form ref="loginFormRef" :model="loginForm" :rules="rules" class="login-form">
        <el-form-item prop="username">
          <el-input 
            v-model="loginForm.username" 
            :placeholder="t('login.placeholder_username')"
            prefix-icon="User"
            size="large"
          />
        </el-form-item>
        
        <el-form-item prop="password">
          <el-input 
            v-model="loginForm.password" 
            type="password" 
            :placeholder="t('login.placeholder_password')"
            prefix-icon="Lock"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        
        <el-form-item>
          <el-button 
            type="primary" 
            size="large" 
            :loading="loading" 
            style="width: 100%"
            @click="handleLogin"
          >
            {{ t('login.login') }}
          </el-button>
        </el-form-item>
        
        <div class="login-tips">
          <span>{{ t('login.tips_account') }}</span>
          <el-tag size="small" @click="fillAccount('admin', 'admin123')">{{ t('login.admin') }}</el-tag>
          <el-tag size="small" @click="fillAccount('merchant', 'merchant123')">{{ t('login.merchant') }}</el-tag>
          <el-tag size="small" @click="fillAccount('user', 'user123')">{{ t('login.user') }}</el-tag>
          <!-- 【2026-09-28】客服账号：超期售后的裁定入口。
               口令与 init.sql 里新增的 kefu 种子账号一致（kefu123）。 -->
          <el-tag size="small" type="warning" @click="fillAccount('kefu', 'kefu123')">{{ t('login.kefu') }}</el-tag>
        </div>
        
        <div class="login-tips">
          <span>{{ t('login.no_account') }}</span>
          <a href="/register" class="link">{{ t('login.register') }}</a>
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
import { login } from '@/api/auth'
import { useSettingsStore } from '@/stores/settings'
import { useUserStore } from '@/stores/user'
import { homePathByRole } from '@/utils/auth'

const router = useRouter()
const { t, locale } = useI18n()
const settingsStore = useSettingsStore()
const userStore = useUserStore()
const loginFormRef = ref()
const loading = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: t('login.username_required'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.password_required'), trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!loginFormRef.value) return
  
  // 校验失败时 validate() 返回的 Promise 会 reject；追加 .catch 兜住，
  // 避免控制台出现 unhandled rejection（失败分支无需额外处理，表单已显示错误）。
  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const res = await login(loginForm)
        // 【收敛】登录态写入统一走 user store（setLogin 同时写 state 与 localStorage），
        // 不再直接 localStorage.setItem，避免登录态双轨。
        userStore.setLogin(res.data)
        ElMessage.success(t('login.login_success'))
        
        // 登录后按角色进入各自的首页（分流规则统一在 @/utils/auth 维护）
        router.push(homePathByRole(res.data.role))
      } catch (error) {
        console.error(error)
      } finally {
        loading.value = false
      }
    }
  }).catch(() => {})
}

const fillAccount = (username, password) => {
  loginForm.username = username
  loginForm.password = password
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
  animation: floatGlow1 10s ease-in-out infinite;
}

.login-container::after {
  width: 480px;
  height: 480px;
  background: rgba(150, 200, 255, 0.55);
  bottom: -140px;
  right: -120px;
  animation: floatGlow2 12s ease-in-out infinite;
}

.dark .login-container::before {
  background: rgba(255, 100, 150, 0.3);
}

.dark .login-container::after {
  background: rgba(100, 150, 255, 0.3);
}

@keyframes floatGlow1 {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(30px, 20px) scale(1.1); }
}

@keyframes floatGlow2 {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(-30px, -20px) scale(1.15); }
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
  width: 420px;
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

.login-tips .el-tag {
  padding: 6px 14px;
  cursor: pointer;
  background: rgb(var(--glass-tint-rgb) / var(--glass-alpha-light));
  backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale)));
  -webkit-backdrop-filter: blur(calc(var(--glass-blur) * 1px * var(--glass-blur-scale)));
  border: 1px solid var(--glass-border);
  border-radius: 20px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  transition: all 0.25s cubic-bezier(0.25, 0.1, 0.25, 1);
  box-shadow:
    0 4px 12px var(--shadow-color),
    inset 0 1px 1px rgba(255, 255, 255, 0.6);
}

.login-tips .el-tag:hover {
  background: var(--accent-light);
  transform: translateY(-1px);
}

.login-tips .link {
  color: var(--accent-color);
  text-decoration: none;
  font-weight: 500;
  margin-left: 4px;
}

.login-tips .link:hover {
  text-decoration: underline;
}
</style>
