<template>
  <div class="product-manage">
    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="page-title">{{ t('product_manage.page_title') }}</span>
          <el-button type="primary" @click="showAddDialog">{{ t('product_manage.add_product') }}</el-button>
        </div>
      </template>
      
      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          :placeholder="t('product_manage.search_name_placeholder')"
          clearable
          @keyup.enter="handleSearch"
          style="width: 250px;" />
        <el-select
          v-model="searchStatus"
          :placeholder="t('product_manage.filter_status_placeholder')"
          style="width: 150px; margin-left: 10px;">
          <el-option :label="t('product_manage.all')" :value="-1" />
          <el-option :label="t('product_manage.on_shelf')" :value="1" />
          <el-option :label="t('product_manage.off_shelf')" :value="0" />
        </el-select>
        <el-button type="primary" @click="handleSearch" style="margin-left: 10px;">{{ t('common.search') }}</el-button>
        <el-button @click="resetSearch" style="margin-left: 10px;">{{ t('common.reset') }}</el-button>
      </div>
      
      <el-table :data="products" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column :label="t('product.images')" width="100" align="center">
          <template #default="{ row }">
            <img
              :src="getImageUrl(row.image)"
              class="product-thumb"
              @error="handleImageError" />
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('product.name')" min-width="200">
          <template #default="{ row }">
            <div class="product-name-cell">
              <span>{{ row.name }}</span>
              <el-tag
                v-if="row.stock <= 30"
                :type="row.stock <= 10 ? 'danger' : 'warning'"
                size="small"
                effect="dark"
                style="margin-left: 8px;">
                {{ row.stock <= 10 ? t('product_manage.stock_urgent') : t('product_manage.stock_low') }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" :label="t('product.category')" width="120" />
        <el-table-column prop="price" :label="t('product_manage.price')" width="100" align="center">
          <template #default="{ row }">
            <span class="price">¥{{ row.price }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="stock" :label="t('product.stock')" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              :type="getStockTagType(row.stock)"
              size="large"
              effect="light">
              {{ row.stock }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 退货期限列：商家需要一眼看出「哪些商品不支持退货」，
             避免上架后才发现规则设错（追责成本远高于这里多看一列）。
             文案口径与买家端 ProductDetail.vue 完全一致（未配置 = 15 天）。 -->
        <el-table-column :label="t('product_manage.return_window')" width="160" align="center">
          <template #default="{ row }">
            <span :class="['return-window-cell', Number(row.returnWindowDays) === 0 ? 'is-none' : '']">
              {{ returnWindowLabel(row.returnWindowDays) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="sales" :label="t('product.sales')" width="100" align="center" />
        <el-table-column prop="status" :label="t('order.status')" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="large">
              {{ row.status === 1 ? t('product_manage.on_shelf') : t('product_manage.off_shelf') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('order.actions')" width="200">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="editProduct(row)">{{ t('common.edit') }}</el-button>
            <el-button type="warning" link size="small" @click="toggleStatus(row)">
              {{ row.status === 1 ? t('product_manage.off_shelf') : t('product_manage.on_shelf') }}
            </el-button>
            <el-button type="danger" link size="small" @click="deleteProduct(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      
      <div class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadProducts"
        />
      </div>
    </el-card>
    
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px">
      <el-form :model="productForm" label-width="100px">
        <el-form-item :label="t('product.name')">
          <el-input v-model="productForm.name" :placeholder="t('product_manage.name_placeholder')" />
        </el-form-item>
        <el-form-item :label="t('product_manage.category')">
          <el-select v-model="productForm.categoryId" :placeholder="t('product_manage.category_placeholder')">
            <el-option 
              v-for="category in categories" 
              :key="category.id" 
              :label="category.name" 
              :value="category.id" 
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('product.images')">
          <div style="margin-bottom: 10px;">
            <img v-if="productForm.image" :src="getImageUrl(productForm.image)" 
                 style="width: 178px; height: 178px; object-fit: cover; border: 1px dashed #d9d9d9; border-radius: 6px;" />
            <div v-else style="width: 178px; height: 178px; border: 1px dashed #d9d9d9; border-radius: 6px; display: flex; align-items: center; justify-content: center;">
              <span style="color: #8c939d;">{{ t('product_manage.no_image') }}</span>
            </div>
          </div>
          <el-upload
            :action="uploadUrl"
            :headers="uploadHeaders"
            :show-file-list="false"
            :on-success="handleImageSuccess"
            :before-upload="beforeImageUpload"
          >
            <el-button type="primary">{{ t('product_manage.select_image') }}</el-button>
          </el-upload>
          <div style="margin-top: 10px; font-size: 12px; color: #909399;">
            {{ t('product_manage.current_image_url') }}<span style="word-break: break-all;">{{ productForm.image || t('product_manage.not_set') }}</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('product_manage.price')">
          <el-input-number v-model="productForm.price" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('product_manage.cost')">
          <el-input-number v-model="productForm.cost" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('product.stock')">
          <el-input-number v-model="productForm.stock" :min="0" style="width: 100%" />
        </el-form-item>
        <!-- ============================================================
             退货退款规则（2026-09-28 新增）
             商家按商品设置退货期限：7/15/30 天、自定义天数、或明确「不支持退货」。
             表单上是一个下拉 + 一个自定义输入框，但**最终只提交一个数值字段**
             returnWindowDays（7 / 15 / 30 / 自定义数值 / 0）—— 这样后端
             Product.returnWindowDays 的语义保持单一，不需要再加一个「档位」字段。
             ============================================================ -->
        <el-form-item :label="t('product_manage.return_window')">
          <div class="return-window-row">
            <el-select v-model="returnWindowPreset" style="width: 180px">
              <el-option :label="t('product_manage.return_window_preset_7')" :value="7" />
              <el-option :label="t('product_manage.return_window_preset_15')" :value="15" />
              <el-option :label="t('product_manage.return_window_preset_30')" :value="30" />
              <el-option :label="t('product_manage.return_window_preset_custom')" :value="CUSTOM_PRESET" />
              <el-option :label="t('product_manage.return_window_preset_none')" :value="0" />
            </el-select>
            <!-- 只有选「自定义」才出现，避免商家误以为这是「天数」输入框 -->
            <template v-if="returnWindowPreset === CUSTOM_PRESET">
              <el-input-number
                v-model="customReturnDays"
                :min="1"
                :max="365"
                :precision="0"
                :step="1"
                style="width: 150px"
              />
              <span class="field-tip">{{ t('product_manage.return_window_custom') }}</span>
            </template>
          </div>
        </el-form-item>
        <el-form-item :label="t('product_manage.return_no_reason')">
          <!-- 期限为「不支持退货退款」时禁用：既然整单不可退，谈「有无理由」没有意义，
               禁用比隐藏更好 —— 商家仍能看到该能力存在，只是当前不适用。 -->
          <el-switch
            v-model="productForm.returnNoReason"
            :active-value="1"
            :inactive-value="0"
            :disabled="returnWindowPreset === 0"
          />
          <span class="field-tip">{{ t('product_manage.return_no_reason_tip') }}</span>
        </el-form-item>
        <el-form-item :label="t('product_manage.description')">
          <el-input v-model="productForm.description" type="textarea" :rows="3" :placeholder="t('product_manage.description_placeholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="saveProduct">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { getProductList, addProduct, updateProduct, deleteProduct as deleteProductApi, updateProductStatus } from '@/api/product'
import { buildAuthHeader } from '@/utils/request'
import { getImageUrl } from '@/utils/image'

// 【R20】全页 i18n：表头/表单项标签复用 product.* 与 order.* 既有键，
// 通用词（操作/编辑/删除/取消/保存/搜索/重置）复用 common.*；
// 本页独有的下拉项、提示与上传校验文案登记在 product_manage.*。
const { t } = useI18n()

const loading = ref(false)
const products = ref([])
const categories = ref([
  { id: 1, name: t('product.category_food') },
  { id: 2, name: t('product.category_digital') },
  { id: 3, name: t('product.category_clothing') },
  { id: 4, name: t('product.category_home') },
  { id: 5, name: t('product.category_fresh') }
])

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const searchKeyword = ref('')
const searchStatus = ref(-1)

const dialogVisible = ref(false)
const dialogTitle = ref(t('product_manage.add_product'))
const productForm = reactive({
  id: null,
  name: '',
  categoryId: null,
  price: 0,
  cost: 0,
  stock: 0,
  image: '',
  description: '',
  // 退货退款规则：null = 未配置（后端按默认 15 天处理），0 = 不支持退货
  returnWindowDays: 15,
  returnNoReason: 1
})

// ==================== 退货期限档位 ====================
// 下拉里的「自定义」是一个**纯 UI 哨兵值**，不会进入 productForm：
// 最终提交的永远只是数值 returnWindowDays。用负值是为了与合法的
// 7/15/30/0 以及 1~365 的自定义天数天然不冲突。
const CUSTOM_PRESET = -1

// 自定义天数的暂存值。选「自定义」时展示/编辑的是它，提交前才落到
// productForm.returnWindowDays —— 这样商家来回切档位时输入不会被清掉。
const customReturnDays = ref(15)

const returnWindowPreset = computed({
  get() {
    const days = productForm.returnWindowDays
    // 7/15/30 命中固定档位；0 = 不支持退货；其余（含 null/undefined）一律按自定义展示
    if (days === 7 || days === 15 || days === 30 || days === 0) {
      return days
    }
    return CUSTOM_PRESET
  },
  set(value) {
    if (value !== CUSTOM_PRESET) {
      productForm.returnWindowDays = value
    } else if (![7, 15, 30, 0].includes(productForm.returnWindowDays)) {
      // 切到「自定义」时保留已有数值（回填场景），仅在确实不是合法档位时赋值
      productForm.returnWindowDays = customReturnDays.value
    }
  }
})

/**
 * 退货期限展示文案。
 *
 * 【为什么和买家端 ProductDetail.vue 各写一份】两页的可用 i18n 键不同：
 * 买家端用的是 product.return_rule_*，商家端只能用 product_manage.*。
 * 但**判定口径必须一致**：0 = 不支持退货；null/undefined = 未配置 = 15 天。
 * 口径不一致会让商家看到「15 天」、买家看到「不支持退货」这类事故。
 */
const returnWindowLabel = (days) => {
  if (days === 0) return t('product_manage.return_window_preset_none')
  if (days === null || days === undefined || days === '') return t('product_manage.return_window_preset_15')
  return t('product.return_rule_days', { days })
}

const uploadUrl = '/api/upload/image'

// 【修复】上传头必须与 axios 拦截器共用同一套规则，见 utils/request.js
// buildAuthHeader() 的说明：后端 Sa-Token 未配置 token-prefix，
// Authorization 里只能放**裸 token**，加 "Bearer " 前缀必然 401
// （实测：裸 token → 200，Bearer 前缀 → 401）。
// 另外必须用 computed：原写法是普通对象，在 setup 时求值一次，
// 用户重新登录（token 变化）后若组件未卸载，会一直带着旧 token 上传。
const uploadHeaders = computed(() => buildAuthHeader())

const loadProducts = async () => {
  loading.value = true
  try {
    const params = { 
      page: currentPage.value, 
      pageSize: pageSize.value 
    }
    if (searchKeyword.value) {
      params.keyword = searchKeyword.value
    }
    if (searchStatus.value >= 0) {
      params.status = searchStatus.value
    }
    const res = await getProductList(params)
    products.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    ElMessage.error(t('product_manage.load_failed'))
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadProducts()
}

const resetSearch = () => {
  searchKeyword.value = ''
  searchStatus.value = -1
  currentPage.value = 1
  loadProducts()
}

const showAddDialog = () => {
  dialogTitle.value = t('product_manage.add_product')
  Object.assign(productForm, { 
    id: null, 
    name: '', 
    categoryId: null, 
    price: 0, 
    cost: 0, 
    stock: 0, 
    image: '', 
    description: '',
    // 新商品默认 15 天 + 支持无理由（与后端 Product 默认口径一致），
    // 不默认成「不支持退货」——那会让商家忘改时商品莫名其妙不能退。
    returnWindowDays: 15,
    returnNoReason: 1
  })
  // 自定义天数暂存值也要复位，否则会残留上一个商品输入的数字
  customReturnDays.value = 15
  dialogVisible.value = true
}

const editProduct = (row) => {
  dialogTitle.value = t('product_manage.edit_product')
  Object.assign(productForm, {
    id: row.id,
    name: row.name,
    categoryId: row.categoryId,
    price: row.price,
    cost: row.cost,
    stock: row.stock,
    image: row.image,
    description: row.description,
    // 回填退货规则：null/undefined 保持原样（= 未配置），由下方档位计算
    // 统一显示成「自定义 + 15」；0 会显示成「不支持退货退款」。
    returnWindowDays: row.returnWindowDays ?? null,
    // 后端 returnNoReason 用 0/1，null（未配置）按「支持」处理，与 AfterSaleEligibility 一致
    returnNoReason: row.returnNoReason === 0 ? 0 : 1
  })
  // 暂存自定义天数：非固定档位时直接沿用商品上的数值，方便商家在此基础上微调
  customReturnDays.value = [7, 15, 30, 0].includes(row.returnWindowDays) ? 15 : (row.returnWindowDays ?? 15)
  dialogVisible.value = true
}

const saveProduct = async () => {
  if (!productForm.name) {
    ElMessage.warning(t('product_manage.name_required'))
    return
  }
  if (!productForm.categoryId) {
    ElMessage.warning(t('product_manage.category_required'))
    return
  }
  // 「自定义天数」必须显式校验：这一档没有下拉选项兜底，1~365 的整数
  // 是后端 ProductController 接受的范围，超范围提交会被拒（或落库成脏数据）。
  if (returnWindowPreset.value === CUSTOM_PRESET) {
    const days = Number(customReturnDays.value)
    if (!Number.isInteger(days) || days < 1 || days > 365) {
      ElMessage.warning(t('product_manage.return_window_invalid'))
      return
    }
    productForm.returnWindowDays = days
  }
  // 兜底：任何路径下最终提交的期限都必须在「0 / 1~365」内
  const finalDays = Number(productForm.returnWindowDays)
  if (!Number.isInteger(finalDays) || finalDays < 0 || finalDays > 365) {
    ElMessage.warning(t('product_manage.return_window_invalid'))
    return
  }
  productForm.returnWindowDays = finalDays

  // 不支持退货时「无理由」开关是禁用态，此时强制归 0，
  // 免得商家先开着开关再改成「不支持退货」，落库留下 returnNoReason=1 的矛盾数据
  if (finalDays === 0) {
    productForm.returnNoReason = 0
  }
  
  try {
    if (productForm.id) {
      await updateProduct(productForm)
      ElMessage.success(t('product_manage.update_success'))
    } else {
      await addProduct(productForm)
      ElMessage.success(t('product_manage.add_success'))
    }
    dialogVisible.value = false
    loadProducts()
  } catch (e) {
    ElMessage.error(t('product_manage.save_failed'))
  }
}

const toggleStatus = async (row) => {
  try {
    const newStatus = row.status === 1 ? 0 : 1
    await updateProductStatus(row.id, newStatus)
    ElMessage.success(t(newStatus === 1 ? 'product_manage.on_shelf_success' : 'product_manage.off_shelf_success'))
    loadProducts()
  } catch (e) {
    ElMessage.error(t('product_manage.operate_failed'))
  }
}

const deleteProduct = async (row) => {
  const productId = row.id
  const productName = row.name
  
  if (!productId) {
    ElMessage.error(t('product_manage.invalid_id'))
    return
  }
  
  try {
    const result = await ElMessageBox.confirm(
      t('product_manage.delete_confirm', { name: productName }),
      t('product_manage.delete_title'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
    if (result === 'confirm') {
      const response = await deleteProductApi(productId)
      
      if (response && response.code === 200) {
        ElMessage.success(t('product_manage.delete_success'))
        currentPage.value = 1
        await loadProducts()
      } else {
        ElMessage.error(t('product_manage.delete_failed') + (response?.message || t('product_manage.unknown_error')))
      }
    }
  } catch (e) {
    if (e !== 'cancel') {
      const errorMsg = e?.response?.data?.message || e?.message || t('product_manage.network_error')
      ElMessage.error(t('product_manage.delete_failed') + errorMsg)
    }
  }
}

const handleImageSuccess = (response, file, fileList) => {
  
  // 从响应中提取URL
  let imageUrl = ''
  
  if (response && response.code === 200) {
    // 尝试多种可能的URL格式
    if (response.data && response.data.url) {
      imageUrl = response.data.url
    } else if (response.url) {
      imageUrl = response.url
    }
  } else if (response && typeof response === 'object' && response.url) {
    imageUrl = response.url
  }
  
  if (imageUrl) {
    // 存储原始URL（不包含/api前缀的路径）
    if (imageUrl.startsWith('/api')) {
      imageUrl = imageUrl.substring(4) // 移除 /api 前缀
    }
    productForm.image = imageUrl
    ElMessage.success(t('product_manage.upload_success'))
  } else {
    console.error(t('product_manage.upload_extract_failed'), response)
    ElMessage.error(t('product_manage.upload_failed'))
  }
}

const beforeImageUpload = (file) => {
  const isJPG = file.type === 'image/jpeg' || file.type === 'image/jpg' || file.type === 'image/png'
  const isLt2M = file.size / 1024 / 1024 < 2

  if (!isJPG) {
    ElMessage.error(t('product_manage.upload_format_error'))
    return false
  }
  if (!isLt2M) {
    ElMessage.error(t('product_manage.upload_size_error'))
    return false
  }
  return true
}

const getStockTagType = (stock) => {
  const q = Number(stock) || 0
  if (q <= 10) return 'danger'
  if (q <= 30) return 'warning'
  return 'success'
}

const handleImageError = (event) => {
  event.target.style.opacity = '0.3'
}

onMounted(() => {
  loadProducts()
})
</script>

<style scoped>
.product-manage {
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
  color: var(--text-primary);
}

.product-thumb {
  width: 60px;
  height: 60px;
  object-fit: cover;
  background: var(--bg-secondary);
  border-radius: 6px;
  border: 1px solid var(--border-color);
}

.product-name-cell {
  display: flex;
  align-items: center;
}

.search-bar {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
  padding: 15px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.price {
  color: #f56c6c;
  font-weight: bold;
}

/* ==================== 退货退款规则（表格列 + 表单行） ==================== */
/* 下拉与自定义输入框并排，输入框只在「自定义」档出现 */
.return-window-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 表单里的补充说明（无理由提示、自定义天数单位）：弱化，不抢主控件视线 */
.field-tip {
  font-size: 12px;
  color: var(--text-muted);
}

/* 「不支持退货」用告警色，商家扫一眼表格就能挑出需要复核的商品 */
.return-window-cell.is-none {
  color: #e6a23c;
  font-weight: 500;
}

.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}

.image-uploader {
  width: 178px;
  height: 178px;
  border: 1px dashed var(--border-color);
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  transition: var(--el-transition-duration-fast);
}

.image-uploader:hover {
  border-color: var(--el-color-primary);
}

.image-uploader-icon {
  font-size: 28px;
  color: var(--text-muted);
  width: 178px;
  height: 178px;
  text-align: center;
  display: flex;
  justify-content: center;
  align-items: center;
}

.image-preview {
  width: 178px;
  height: 178px;
  display: block;
  object-fit: cover;
}
</style>
