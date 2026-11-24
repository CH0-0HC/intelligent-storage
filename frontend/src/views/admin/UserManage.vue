<template>
  <div class="user-manage">
    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="page-title">{{ t('user.title') }}</span>
          <el-button type="primary" @click="showAddDialog">{{ t('user_manage.add_user') }}</el-button>
        </div>
      </template>
      
      <el-table :data="users" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" :label="t('user.username')" width="150" />
        <el-table-column prop="nickname" :label="t('user.nickname')" width="150" />
        <el-table-column prop="role" :label="t('user.role')" width="120">
          <template #default="{ row }">
            <el-tag :type="getRoleType(row.role)">{{ getRoleText(row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="phone" :label="t('user.phone')" width="150" />
        <el-table-column prop="email" :label="t('user.email')" width="200" />
        <el-table-column prop="status" :label="t('user.status')" width="100">
          <template #default="{ row }">
            <el-switch 
              v-model="row.status" 
              :active-value="1" 
              :inactive-value="0"
              @change="toggleStatus(row)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" :label="t('user_manage.last_login')" :formatter="dateTimeFormatter" width="180" />
        <el-table-column :label="t('order.actions')" width="150">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="editUser(row)">{{ t('common.edit') }}</el-button>
            <el-button type="danger" link size="small" @click="deleteUser(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form :model="formData" label-width="80px">
        <el-form-item :label="t('user.username')">
          <el-input v-model="formData.username" :disabled="isEdit" />
        </el-form-item>
        <el-form-item :label="t('login.password')">
          <el-input v-model="formData.password" type="password" :placeholder="isEdit ? t('user_manage.password_edit_placeholder') : ''" />
        </el-form-item>
        <el-form-item :label="t('user.nickname')">
          <el-input v-model="formData.nickname" />
        </el-form-item>
        <el-form-item :label="t('user.role')">
          <el-select v-model="formData.role">
            <el-option :label="t('login.admin')" value="admin" />
            <el-option :label="t('login.merchant')" value="merchant" />
            <el-option :label="t('login.user')" value="user" />
            <!-- 【2026-09-28 新增角色】超期售后需要「客服依据商家统一标准裁定」，
                 因此后台必须能创建客服账号 —— 否则该角色只能靠改库产生，
                 与「新增独立 kefu 客服角色」的交付要求不符。
                 注意：注册页刻意**不**开放 kefu 选项（见 Register.vue 注释），
                 客服只能由管理员在此创建。 -->
            <el-option :label="t('login.kefu')" value="kefu" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('user.phone')">
          <el-input v-model="formData.phone" />
        </el-form-item>
        <el-form-item :label="t('user.email')">
          <el-input v-model="formData.email" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="saveUser">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getUserList, addUser, updateUser, deleteUser as deleteUserApi } from '@/api/user'
import { dateTimeFormatter } from '@/utils/datetime'

// 【R20】全页 i18n：表头、弹窗、按钮与 Toast/console 提示统一走语言包。
// 复用 user.* 的表头键、login.* 的角色名与 common.* 通用词；
// 角色兜底复用 order.unknown —— 与第 4 页 OrderManage 同一约定，避免再登记同义的「未知」键。
const { t } = useI18n()

const users = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref(t('user_manage.add_user'))
const isEdit = ref(false)
const formData = ref({
  id: null,
  username: '',
  password: '',
  nickname: '',
  role: 'user',
  phone: '',
  email: '',
  status: 1
})

const loadUsers = async () => {
  loading.value = true
  try {
    const res = await getUserList()
    users.value = res.data
  } catch (error) {
    ElMessage.error(t('user_manage.load_failed'))
  } finally {
    loading.value = false
  }
}

const getRoleText = (role) => {
  const map = {
    admin: t('login.admin'),
    merchant: t('login.merchant'),
    user: t('login.user'),
    // 【2026-09-28】超期售后的裁定人角色
    kefu: t('login.kefu')
  }
  return map[role] || t('order.unknown')
}

const getRoleType = (role) => {
  // kefu 用 success（绿）区分于 merchant 的 warning（橙），
  // 避免与商家混色 —— 两者在售后链路里是对立的双方。
  const map = { admin: 'danger', merchant: 'warning', user: 'primary', kefu: 'success' }
  return map[role] || 'info'
}

const showAddDialog = () => {
  isEdit.value = false
  dialogTitle.value = t('user_manage.add_user')
  formData.value = {
    id: null,
    username: '',
    password: '',
    nickname: '',
    role: 'user',
    phone: '',
    email: '',
    status: 1
  }
  dialogVisible.value = true
}

const editUser = (user) => {
  isEdit.value = true
  dialogTitle.value = t('user_manage.edit_user')
  formData.value = {
    id: user.id,
    username: user.username,
    password: '',
    nickname: user.nickname,
    role: user.role,
    phone: user.phone,
    email: user.email,
    status: user.status
  }
  dialogVisible.value = true
}

const saveUser = async () => {
  try {
    if (isEdit.value) {
      await updateUser(formData.value)
      ElMessage.success(t('user_manage.update_success'))
    } else {
      await addUser(formData.value)
      ElMessage.success(t('user_manage.add_success'))
    }
    dialogVisible.value = false
    loadUsers()
  } catch (error) {
    ElMessage.error(t('user_manage.save_failed'))
  }
}

const deleteUser = async (user) => {
  try {
    await ElMessageBox.confirm(
      t('user_manage.delete_confirm', { username: user.username }),
      t('common.warning')
    )
    await deleteUserApi(user.id)
    ElMessage.success(t('user_manage.delete_success'))
    loadUsers()
  } catch {}
}

const toggleStatus = async (user) => {
  try {
    await updateUser(user)
    ElMessage.success(t(user.status === 1 ? 'user_manage.enabled' : 'user_manage.disabled'))
  } catch (error) {
    ElMessage.error(t('user_manage.status_update_failed'))
    user.status = user.status === 1 ? 0 : 1
  }
}

onMounted(() => {
  loadUsers()
})
</script>

<style scoped>
.user-manage {
  padding: 20px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}
</style>
