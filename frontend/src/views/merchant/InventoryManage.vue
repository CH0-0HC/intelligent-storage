<template>
  <div class="inventory-manage">
    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="page-title">{{ t('inventory.title') }}</span>
          <div>
            <el-button type="success" @click="handleSyncStock">{{ t('inventory.sync_stock') }}</el-button>
            <el-button type="primary" @click="showInDialog">{{ t('inventory.in_stock') }}</el-button>
            <el-button type="warning" @click="showOutDialog">{{ t('inventory.out_stock') }}</el-button>
          </div>
        </div>
      </template>
      
      <el-table :data="inventories" stripe v-loading="loading">
        <el-table-column prop="productId" :label="t('inventory_manage.product_id')" width="100" align="center" />
        <el-table-column prop="productName" :label="t('product.name')" min-width="200">
          <template #default="{ row }">
            <div class="inventory-name-cell">
              <span>{{ row.productName }}</span>
              <el-tag
                v-if="row.quantity <= row.warningQuantity"
                type="danger"
                size="small"
                effect="dark"
                style="margin-left: 8px;">
                {{ t('inventory.warning') }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" :label="t('inventory_manage.current_stock')" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              :type="getInventoryTagType(row.quantity, row.warningQuantity)"
              size="large"
              effect="light">
              {{ row.quantity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="warningQuantity" :label="t('inventory_manage.warning_quantity')" width="120" align="center" />
        <el-table-column prop="lastInTime" :label="t('inventory_manage.last_in_time')" :formatter="dateTimeFormatter" width="180" align="center" />
        <el-table-column prop="lastOutTime" :label="t('inventory_manage.last_out_time')" :formatter="dateTimeFormatter" width="180" align="center" />
        <el-table-column :label="t('inventory_manage.stock_status')" width="120" align="center">
          <template #default="{ row }">
            <span v-if="row.quantity <= row.warningQuantity" class="status-warning">
              {{ t('inventory_manage.below_warning') }}
            </span>
            <span v-else-if="row.quantity <= row.warningQuantity * 2" class="status-low">
              {{ t('inventory_manage.low') }}
            </span>
            <span v-else class="status-good">{{ t('inventory_manage.normal') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('order.actions')" width="220" align="center">
          <template #default="{ row }">
            <el-button type="success" link size="small" @click="showStockInForProduct(row)">{{ t('inventory_manage.quick_stock_in') }}</el-button>
            <el-button type="warning" link size="small" @click="viewLog(row)">{{ t('inventory_manage.detail') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="inDialogVisible" :title="t('inventory_manage.stock_in_title')" width="500px">
      <el-form :model="inForm" label-width="100px">
        <el-form-item :label="t('inventory_manage.select_product')">
          <el-select v-model="inForm.productId" :placeholder="t('inventory_manage.select_product_placeholder')" filterable style="width: 100%">
            <el-option 
              v-for="product in productList" 
              :key="product.id" 
              :label="product.name" 
              :value="product.id" 
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inventory_manage.stock_in_quantity')">
          <el-input-number v-model="inForm.quantity" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('inventory_manage.stock_in_type')">
          <el-select v-model="inForm.type" :placeholder="t('inventory_manage.select_type_placeholder')" style="width: 100%">
            <el-option :label="t('inventory_manage.type_purchase')" value="purchase" />
            <el-option :label="t('inventory_manage.type_return_in')" value="return" />
            <el-option :label="t('inventory_manage.type_check_in')" value="check_in" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inventory_manage.remark')">
          <el-input v-model="inForm.remark" type="textarea" :rows="2" :placeholder="t('inventory_manage.remark_placeholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleStockIn">{{ t('inventory_manage.confirm_stock_in') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="outDialogVisible" :title="t('inventory_manage.stock_out_title')" width="500px">
      <el-form :model="outForm" label-width="100px">
        <el-form-item :label="t('inventory_manage.select_product')">
          <el-select v-model="outForm.productId" :placeholder="t('inventory_manage.select_product_placeholder')" filterable style="width: 100%">
            <el-option 
              v-for="product in productList" 
              :key="product.id" 
              :label="product.name" 
              :value="product.id" 
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inventory_manage.stock_out_quantity')">
          <el-input-number v-model="outForm.quantity" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('inventory_manage.stock_out_type')">
          <el-select v-model="outForm.type" :placeholder="t('inventory_manage.select_type_placeholder')" style="width: 100%">
            <el-option :label="t('inventory_manage.type_sale')" value="sale" />
            <el-option :label="t('inventory_manage.type_return_out')" value="return_out" />
            <el-option :label="t('inventory_manage.type_damage')" value="damage" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inventory_manage.remark')">
          <el-input v-model="outForm.remark" type="textarea" :rows="2" :placeholder="t('inventory_manage.remark_placeholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="outDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleStockOut">{{ t('inventory_manage.confirm_stock_out') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logDialogVisible" :title="t('inventory_manage.log_title')" width="800px">
      <el-table :data="inventoryLogs" stripe>
        <el-table-column prop="productId" :label="t('inventory_manage.product_id')" width="100" />
        <el-table-column prop="type" :label="t('inventory_manage.type')" width="100">
          <template #default="{ row }">
            <el-tag :type="row.type === 0 ? 'success' : 'danger'">
              {{ row.type === 0 ? t('inventory.in_stock') : t('inventory.out_stock') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" :label="t('inventory_manage.change_quantity')" width="100" />
        <el-table-column prop="beforeQuantity" :label="t('inventory_manage.before_quantity')" width="100" />
        <el-table-column prop="afterQuantity" :label="t('inventory_manage.after_quantity')" width="100" />
        <el-table-column prop="orderType" :label="t('inventory_manage.order_type')" width="120" />
        <el-table-column prop="remark" :label="t('inventory_manage.remark')" min-width="150" />
        <el-table-column prop="createTime" :label="t('inventory_manage.time')" :formatter="dateTimeFormatterSeconds" width="180" />
      </el-table>
      <template #footer>
        <el-button @click="logDialogVisible = false">{{ t('order_manage.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getInventoryList, getInventoryLogList, stockIn, stockOut, syncStock } from '@/api/inventory'
import { getProductList } from '@/api/product'
import { dateTimeFormatter, dateTimeFormatterSeconds } from '@/utils/datetime'

// 【R20】全页 i18n：页标题/按钮复用 inventory.* 既有键（title / sync_stock / in_stock /
// out_stock / warning），商品名称复用 product.name、「操作」列复用 order.actions、
// 「关闭」复用 order_manage.close、取消/确定复用 common.*；本页独有文案登记在 inventory_manage.*。
const { t } = useI18n()

const loading = ref(false)
const inventories = ref([])
const productList = ref([])
const inventoryLogs = ref([])

const inDialogVisible = ref(false)
const outDialogVisible = ref(false)
const logDialogVisible = ref(false)

const inForm = ref({
  productId: null,
  quantity: 1,
  type: 'purchase',
  remark: ''
})

const outForm = ref({
  productId: null,
  quantity: 1,
  type: 'sale',
  remark: ''
})

const loadInventories = async () => {
  loading.value = true
  try {
    const res = await getInventoryList({ page: 1, pageSize: 100 })
    inventories.value = res.data?.records || []
  } catch (e) {
    ElMessage.error(t('inventory_manage.load_failed'))
  } finally {
    loading.value = false
  }
}

const loadProductList = async () => {
  try {
    const res = await getProductList({ page: 1, pageSize: 1000 })
    productList.value = res.data?.records || []
  } catch (e) {
    console.error(t('inventory_manage.load_products_failed'), e)
  }
}

const showInDialog = () => {
  inForm.value = { productId: null, quantity: 1, type: 'purchase', remark: '' }
  inDialogVisible.value = true
}

const showOutDialog = () => {
  outForm.value = { productId: null, quantity: 1, type: 'sale', remark: '' }
  outDialogVisible.value = true
}

const handleStockIn = async () => {
  if (!inForm.value.productId) {
    ElMessage.warning(t('inventory_manage.select_product_required'))
    return
  }
  if (!inForm.value.quantity || inForm.value.quantity < 1) {
    ElMessage.warning(t('inventory_manage.quantity_invalid'))
    return
  }
  
  try {
    await stockIn(inForm.value)
    ElMessage.success(t('inventory_manage.stock_in_success'))
    inDialogVisible.value = false
    loadInventories()
  } catch (e) {
    ElMessage.error(e.message || t('inventory_manage.stock_in_failed'))
  }
}

const handleStockOut = async () => {
  if (!outForm.value.productId) {
    ElMessage.warning(t('inventory_manage.select_product_required'))
    return
  }
  if (!outForm.value.quantity || outForm.value.quantity < 1) {
    ElMessage.warning(t('inventory_manage.quantity_invalid'))
    return
  }
  
  try {
    await stockOut(outForm.value)
    ElMessage.success(t('inventory_manage.stock_out_success'))
    outDialogVisible.value = false
    loadInventories()
  } catch (e) {
    ElMessage.error(e.message || t('inventory_manage.stock_out_failed'))
  }
}

const viewLog = async (row) => {
  try {
    const res = await getInventoryLogList({ productId: row.productId, page: 1, pageSize: 50 })
    inventoryLogs.value = res.data?.records || []
    logDialogVisible.value = true
  } catch (e) {
    ElMessage.error(t('inventory_manage.load_log_failed'))
  }
}

const getInventoryTagType = (quantity, warningQty) => {
  const q = Number(quantity) || 0
  const w = Number(warningQty) || 0
  if (q <= w) return 'danger'
  if (q <= w * 2) return 'warning'
  return 'success'
}

const showStockInForProduct = (row) => {
  inForm.value = {
    productId: row.productId,
    quantity: 10,
    type: 'purchase',
    remark: t('inventory_manage.restock_remark', { name: row.productName })
  }
  inDialogVisible.value = true
}

const handleSyncStock = async () => {
  try {
    await ElMessageBox.confirm(
      t('inventory_manage.sync_confirm'),
      t('inventory.sync_stock'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
    
    const res = await syncStock()
    ElMessage.success(t('inventory_manage.sync_success', {
      total: res.data?.totalProducts || 0,
      updated: res.data?.updatedCount || 0,
      created: res.data?.createdCount || 0
    }))
    loadInventories()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e.message || t('inventory_manage.sync_failed'))
    }
  }
}

onMounted(() => {
  loadInventories()
  loadProductList()
})
</script>

<style scoped>
.inventory-manage {
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

.inventory-name-cell {
  display: flex;
  align-items: center;
}

.status-warning {
  color: #f56c6c;
  font-weight: 600;
  font-size: 13px;
}

.status-low {
  color: #e6a23c;
  font-weight: 500;
  font-size: 13px;
}

.status-good {
  color: #67c23a;
  font-weight: 500;
  font-size: 13px;
}
</style>
