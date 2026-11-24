<template>
  <div class="profile-page">
    <el-card class="profile-card">
      <template #header>
        <span class="page-title">{{ t('profile.title') }}</span>
      </template>
      
      <div class="profile-content">
        <div class="avatar-section">
          <el-avatar :size="120" icon="UserFilled" class="user-avatar" />
          <h2 class="user-name">{{ userInfo.nickname || userInfo.username }}</h2>
          <div class="user-role">
            <el-tag :type="getRoleType(userInfo.role)">{{ getRoleText(userInfo.role) }}</el-tag>
          </div>
        </div>
        
        <div class="info-section">
          <h3 class="section-title">{{ t('profile.basic_info') }}</h3>
          <el-form :model="userInfo" label-width="120px">
            <el-form-item :label="t('profile.username')">
              <el-input v-model="userInfo.username" disabled />
            </el-form-item>
            <el-form-item :label="t('profile.nickname')">
              <el-input v-model="userInfo.nickname" />
            </el-form-item>
            <el-form-item :label="t('profile.email')">
              <el-input v-model="userInfo.email" />
            </el-form-item>
            <el-form-item :label="t('profile.phone')">
              <el-input v-model="userInfo.phone" />
            </el-form-item>
            <el-form-item :label="t('profile.create_time')">
              <el-input v-model="formattedCreateTime" disabled />
            </el-form-item>
          </el-form>
        </div>
        
        <div class="action-section">
          <el-button type="primary" @click="saveProfile">{{ t('profile.save') }}</el-button>
          <el-button @click="$router.push('/user/password')">{{ t('profile.change_password') }}</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { getCurrentUser, updateProfile } from '@/api/auth'

const { t } = useI18n()

const userInfo = ref({
  id: '',
  username: '',
  nickname: '',
  email: '',
  phone: '',
  role: '',
  createTime: ''
})

const loading = ref(false)

const formattedCreateTime = computed(() => {
  if (!userInfo.value.createTime) return ''
  return new Date(userInfo.value.createTime).toLocaleString('zh-CN')
})

// 【i18n】角色展示文案走 i18n（切换语言即时生效）。
// 注意：这里只影响「显示」，角色判断本身一律走 @/utils/auth 的常量，
// 不要用翻译后的文本做比较。
const getRoleText = (role) => {
  const roles = {
    admin: 'profile.role_admin',
    merchant: 'profile.role_merchant',
    user: 'profile.role_user'
  }
  return roles[role] ? t(roles[role]) : t('profile.role_unknown')
}

const getRoleType = (role) => {
  const types = {
    admin: 'danger',
    merchant: 'warning',
    user: 'success'
  }
  return types[role] || 'info'
}

const loadProfile = async () => {
  loading.value = true
  try {
    const res = await getCurrentUser()
    if (res.code === 200 && res.data) {
      userInfo.value = {
        ...userInfo.value,
        ...res.data
      }
    }
  } catch (error) {
    console.error('加载用户信息失败', error)
  } finally {
    loading.value = false
  }
}

const saveProfile = async () => {
  try {
    // 【修复】改调 PUT /auth/profile（自助接口，id 由服务端从登录态取）。
    // 原实现调 updateUser → PUT /user/update，而该接口属 UserController 类级
    // @SaCheckRole("admin")，只对管理员开放；但个人中心路由 meta.roles 是
    // ['admin','merchant','user']，三个角色都能进这页 —— 一旦角色鉴权生效，
    // 普通用户和商家点「保存修改」会立刻 403。
    // 同时不再把 id 放进请求体：原写法允许篡改 id 去改他人资料（提权）。
    await updateProfile({
      nickname: userInfo.value.nickname,
      email: userInfo.value.email,
      phone: userInfo.value.phone
    })
    ElMessage.success(t('profile.update_success'))
  } catch (error) {
    ElMessage.error(t('profile.update_failed'))
  }
}

onMounted(() => {
  loadProfile()
})
</script>

<style scoped>
.profile-page {
  padding: 20px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.profile-card {
  max-width: 600px;
  margin: 0 auto;
}

.profile-content {
  text-align: center;
}

.avatar-section {
  margin-bottom: 30px;
  padding-bottom: 30px;
  border-bottom: 1px solid #f0f0f0;
}

.user-avatar {
  margin-bottom: 15px;
}

.user-name {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
}

.user-role {
  margin-top: 10px;
}

.info-section {
  text-align: left;
  margin-bottom: 30px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 20px;
}

.action-section {
  display: flex;
  gap: 15px;
  justify-content: center;
}
</style>