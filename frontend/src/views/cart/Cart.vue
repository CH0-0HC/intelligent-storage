<template>
  <div class="cart-page">
    <el-card>
      <template #header>
        <span class="page-title">{{ t('cart.title') }}</span>
      </template>

      <el-alert
        v-if="outOfStockItems.length > 0"
        :title="t('cart.out_of_stock_title')"
        type="error"
        :closable="false"
        style="margin-bottom: 12px"
      >
        <template v-for="item in outOfStockItems" :key="item.id">
          {{ item.productName || item.product?.name }}
        </template>
      </el-alert>

      <el-alert
        v-if="insufficientStockItems.length > 0"
        :title="t('cart.insufficient_stock_title')"
        type="warning"
        :closable="false"
        style="margin-bottom: 12px"
      >
        <div v-for="item in insufficientStockItems" :key="item.id">
          {{ t('cart.insufficient_stock_line', { name: item.productName || item.product?.name, stock: item.productStock, qty: item.quantity }) }}
        </div>
      </el-alert>

      <el-table :data="cartItems" stripe v-if="cartItems.length > 0">
        <!-- 勾选列（2026-09-22 新增）
             刻意不用 el-table 的 type="selection"：那套 API 需要「数据加载后
             再 toggleRowSelection 回填」的两步走，且会与 selection-change 相互触发，
             容易出现「界面勾了、状态没存」或反复持久化。
             这里直接让勾选框绑定服务端返回的 row.selected，单一数据源、天然幂等。 -->
        <el-table-column width="50" align="center">
          <template #header>
            <el-checkbox
              :model-value="allSelected"
              :indeterminate="isIndeterminate"
              @change="onSelectAllChange"
            />
          </template>
          <template #default="{ row }">
            <el-checkbox
              :model-value="row.selected === 1"
              @change="(checked) => onRowSelectChange(row, checked)"
            />
          </template>
        </el-table-column>

        <el-table-column :label="t('cart.product_info')" min-width="300">
          <template #default="{ row }">
            <div class="product-info">
              <img :src="getImageUrl(row.product?.image)" class="product-image" @error="($event.target.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI4MCIgaGVpZ2h0PSI4MCIgdmlld0JveD0iMCAwIDgwIDgwIj48cmVjdCB3aWR0aD0iODAiIGhlaWdodD0iODAiIGZpbGw9IiNmNWY3ZmEiIHJ4PSI0Ii8+PHRleHQgeD0iNDAiIHk9IjQ1IiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBmaWxsPSIjOTA5MzkiIGZvbnQtc2l6ZT0iMTAiIGZvbnQtZmFtaWx5PSJBcmlhbCI+Tm8gSW1hZ2U8L3RleHQ+PC9zdmc+')" />
              <div class="product-detail">
                <div class="product-name">{{ row.product?.name }}</div>
                <div class="product-price">¥{{ row.product?.price }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('cart.quantity')" width="200">
          <template #default="{ row }">
            <!-- 【测试报告 P3-2】数量输入原先只挂了 @change。
                 但 Element Plus 的 el-input-number 在「当前值已是库存上限、用户又输更大的数」
                 这一情形下会把值夹回原值，其 setCurrentValue() 命中
                 `if (oldVal === newVal && value) return` 直接返回 —— change 不派发，
                 于是不调接口、不弹提示，输入框默默跳回 494。
                 这里补两条：@input 记录**夹取前**的原始键入值，@blur 兜底检测，
                 超库存时给出与详情页口径一致的明确提示。 -->
            <el-input-number
              v-model="row.quantity"
              :min="1"
              :max="row.product?.stock || 99"
              @input="onQuantityTyped"
              @change="updateQuantity(row)"
              @blur="onQuantityBlurred(row)"
            />
          </template>
        </el-table-column>

        <el-table-column :label="t('cart.subtotal')" width="150">
          <template #default="{ row }">
            <span class="subtotal">¥{{ calculateSubtotal(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('cart.actions')" width="100">
          <template #default="{ row }">
            <el-button type="danger" link @click="removeItem(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="empty-cart">
        <el-icon :size="64" class="empty-icon">ShoppingCart</el-icon>
        <p>{{ t('cart.empty') }}</p>
        <el-button type="primary" @click="goToProducts">{{ t('cart.go_shopping') }}</el-button>
      </div>

      <div v-if="cartItems.length > 0" class="cart-footer">
        <div class="footer-selection">
          <el-checkbox
            :model-value="allSelected"
            :indeterminate="isIndeterminate"
            @change="onSelectAllChange"
          >
            {{ t('cart.select_all') }}
          </el-checkbox>
          <span class="selection-hint">
            {{ t('cart.selected_count') }}
            <b>{{ selectedCount }}</b> / {{ cartItems.length }} {{ t('cart.item_unit') }}
          </span>
        </div>

        <div class="footer-actions">
          <div class="cart-summary">
            <div class="summary-row">
              <span>{{ t('cart.total_amount') }}：</span>
              <span>¥{{ totalPrice }}</span>
            </div>
            <div class="summary-row total">
              <span>{{ t('cart.final_amount') }}：</span>
              <span class="total-price">¥{{ totalPrice }}</span>
            </div>
          </div>

          <el-button
            type="primary"
            size="large"
            @click="checkout"
            :disabled="selectedCount === 0"
            :loading="checkingOut"
          >
            {{ t('cart.checkout') }}<template v-if="selectedCount > 0">（{{ selectedCount }}）</template>
          </el-button>
        </div>
      </div>
    </el-card>

    <el-dialog :title="t('cart.confirm_order')" v-model="showOrderConfirm" width="500px">
      <div class="order-confirm">
        <div class="confirm-section">
          <h4>{{ t('cart.order_items') }}</h4>
          <div v-for="item in selectedItems" :key="item.id" class="confirm-item">
            <span>{{ item.product.name }} x {{ item.quantity }}</span>
            <span>¥{{ calculateSubtotal(item) }}</span>
          </div>
        </div>

        <div class="confirm-section">
          <h4>{{ t('order.shipping_info') }}</h4>
          <!-- 【测试报告#2】原先只在 label 上挂了 required 星号，form 没有 :rules、
               item 没有 prop —— 规则根本挂不上去，电话填 "abc" 也能下单入库。
               现在补 rules + prop，字段级红字提示，并与商品详情「立即购买」共用同一份校验。 -->
          <el-form ref="shippingFormRef" :model="shippingForm" :rules="shippingRules" label-width="80px" size="default">
            <el-form-item :label="t('order.shipping_name')" prop="name">
              <el-input v-model="shippingForm.name" :placeholder="t('order.shipping_name_placeholder')" />
            </el-form-item>
            <el-form-item :label="t('order.shipping_phone')" prop="phone">
              <el-input v-model="shippingForm.phone" :placeholder="t('order.shipping_phone_placeholder')" />
            </el-form-item>
            <el-form-item :label="t('order.shipping_address')" prop="address">
              <el-input v-model="shippingForm.address" :placeholder="t('order.shipping_address_placeholder')" type="textarea" :rows="2" />
            </el-form-item>
          </el-form>
        </div>

        <div class="confirm-section total-section">
          <span class="total-label">{{ t('cart.total_amount') }}</span>
          <span class="total-amount">¥{{ totalPrice }}</span>
        </div>
      </div>

      <template #footer>
        <el-button @click="showOrderConfirm = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitOrder" :loading="submitting">{{ t('cart.submit_order') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ShoppingCart } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { getImageUrl } from '@/utils/image'
import {
  getCartList,
  updateCartQuantity,
  removeFromCart,
  updateCartSelected,
  updateAllCartSelected
} from '@/api/cart'
import { createOrder } from '@/api/order'
import { makeShippingRules } from '@/utils/shippingRules'
import { detectStockClamp } from '@/utils/stockClamp'

const { t } = useI18n()

const router = useRouter()
const cartItems = ref([])
const showOrderConfirm = ref(false)
const checkingOut = ref(false)
const submitting = ref(false)
const loading = ref(false)

const shippingForm = ref({
  name: '',
  phone: '',
  address: ''
})

// 【测试报告#2】收货表单校验规则 + el-form 实例；校验实现见 utils/shippingRules.js
const shippingFormRef = ref(null)
const shippingRules = makeShippingRules(t)

// ============ 勾选（2026-09-22 新增） ============
// 单一数据源：row.selected（由后端 cart.selected 返回）。
// 勾选状态写回服务端，因此刷新页面 / 重新登录后仍会保持。

/** 已勾选的条目 */
const selectedItems = computed(() => cartItems.value.filter(item => item.selected === 1))

const selectedCount = computed(() => selectedItems.value.length)

/** 是否全选（空购物车不算全选，避免空列表显示成「已全选」） */
const allSelected = computed(() =>
  cartItems.value.length > 0 && selectedItems.value.length === cartItems.value.length
)

/** 半选态：勾了但没勾满 */
const isIndeterminate = computed(() => selectedCount.value > 0 && !allSelected.value)

/** 金额只统计「已勾选」的商品 —— 与结算范围严格一致 */
const totalPrice = computed(() => {
  return selectedItems.value.reduce((sum, item) => {
    return sum + (item.productPrice || 0) * item.quantity
  }, 0).toFixed(2)
})

/**
 * 勾选/取消勾选单件商品。
 *
 * 采用「乐观更新 + 失败回滚」：先改本地让界面立刻响应，写库失败再退回原值。
 * 失败时**不再弹 ElMessage** —— utils/request.js 的响应拦截器已经统一提示过，
 * 这里再弹会变成两句重复的报错。
 */
const onRowSelectChange = async (row, checked) => {
  const target = checked ? 1 : 0
  if (row.selected === target) {
    return
  }
  const previous = row.selected
  row.selected = target

  try {
    await updateCartSelected(row.id, target)
  } catch (error) {
    row.selected = previous
    console.error('保存勾选状态失败', error)
  }
}

/** 全选 / 取消全选（批量接口，避免 N 次往返） */
const onSelectAllChange = async (checked) => {
  const target = checked ? 1 : 0
  const previous = cartItems.value.map(item => item.selected)

  cartItems.value.forEach(item => { item.selected = target })

  try {
    await updateAllCartSelected(target)
  } catch (error) {
    cartItems.value.forEach((item, index) => { item.selected = previous[index] })
    console.error('保存全选状态失败', error)
  }
}

// 校验商品状态与库存（全量，用于页面顶部的提示）
const outOfStockItems = computed(() => {
  return cartItems.value.filter(item => !item.productStock || item.productStock <= 0)
})

const insufficientStockItems = computed(() => {
  return cartItems.value.filter(item => {
    return item.productStock && item.productStock > 0 && item.quantity > item.productStock
  })
})

// 结算阻塞判定只看**已勾选**的条目：
// 购物车里放着一件缺货商品但没勾选它，不应该影响其它商品正常结算。
const selectedOutOfStock = computed(() =>
  selectedItems.value.filter(item => !item.productStock || item.productStock <= 0)
)

const selectedInsufficient = computed(() =>
  selectedItems.value.filter(item => item.productStock && item.productStock > 0 && item.quantity > item.productStock)
)

const calculateSubtotal = (item) => {
  return ((item.productPrice || 0) * item.quantity).toFixed(2)
}

const loadCart = async () => {
  loading.value = true
  try {
    const res = await getCartList()
    cartItems.value = (res.data || []).map(item => ({
      ...item,
      // 兜底归一化：库里 selected 已是 NOT NULL DEFAULT 1，但历史数据/异常写入
      // 仍可能给出 null，会让勾选框显示为空且「已选」计数失真。
      selected: item.selected === 0 ? 0 : 1,
      product: {
        id: item.productId,
        name: item.productName,
        price: item.productPrice,
        image: item.productImage,
        stock: item.productStock
      }
    }))
  } catch (error) {
    console.error('加载购物车失败', error)
  } finally {
    loading.value = false
  }
}

/**
 * 【测试报告 P3-2】最近一次被 el-input-number 夹取前的原始键入值。
 * 由 @input 写入，change / blur 消费后立刻清空 —— 同一次操作只会弹一次，
 * 也不会把上一次的残留带到下一次失焦。
 */
const pendingTypedQuantity = ref(null)

const onQuantityTyped = (value) => {
  pendingTypedQuantity.value = value
}

/** 当前这一行是否存在「键入值 > 库存」的夹取（详见 utils/stockClamp.js） */
const clampOf = (item) =>
  detectStockClamp(pendingTypedQuantity.value, item.product?.stock)

const updateQuantity = async (item) => {
  const clamp = clampOf(item)
  pendingTypedQuantity.value = null
  try {
    await updateCartQuantity(item.id, { quantity: item.quantity })
    if (clamp) {
      // 夹掉了就明说夹成多少，与详情页「已超出库存」的提示口径对齐
      ElMessage.warning(t('cart.quantity_clamped', { stock: clamp.stock }))
    } else {
      ElMessage.success(t('cart.quantity_updated'))
    }
  } catch (error) {
    console.error('更新数量失败', error)
  }
}

/**
 * 失焦兜底 —— 本条修复的关键。
 * 当前值本来就是库存上限时，夹回的值等于原值，Element Plus 的
 * `setCurrentValue()` 会 `return` 掉，`change` 根本不派发（既不调接口也不提示）。
 * `@blur` 是唯一还能看见「用户刚才输了 600」的时机，所以在这里补发提示。
 * change 已经消费过 pendingTypedQuantity 时此处读到 null，不会重复弹。
 */
const onQuantityBlurred = (item) => {
  const clamp = clampOf(item)
  pendingTypedQuantity.value = null
  if (clamp) {
    ElMessage.warning(t('cart.quantity_clamped', { stock: clamp.stock }))
  }
}

const removeItem = async (item) => {
  try {
    await ElMessageBox.confirm(t('cart.delete_confirm', { name: item.productName || item.product?.name }), t('common.warning'))
    await removeFromCart(item.id)
    const index = cartItems.value.findIndex(i => i.id === item.id)
    if (index > -1) {
      cartItems.value.splice(index, 1)
    }
    ElMessage.success(t('cart.deleted'))
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败', error)
    }
  }
}

const goToProducts = () => {
  router.push('/products')
}

const checkout = () => {
  // 未勾选任何商品时不允许进入结算流程
  // （按钮本身也已 disabled，这里是防御性兜底）
  // 【测试报告#4】三道闸：按钮 :disabled → 这里的 early return → submitOrder 里再一道，
  // 保证「已选 0 件」绝不可能开出一份总额 ¥0.00 的空清单弹窗
  if (selectedCount.value === 0) {
    ElMessage.warning(t('cart.select_required'))
    return
  }
  showOrderConfirm.value = true
  // 上次提交留下的红字不要带到新的一次填写里
  shippingFormRef.value?.clearValidate()
}

const submitOrder = async () => {
  // 【测试报告#4】提交前再确认一次勾选项非空：后端对此返回 400「请至少选择一个商品」，
  // 但那是兜底，不该让用户先看到空弹窗再被后端拒。
  if (selectedCount.value === 0) {
    ElMessage.warning(t('cart.select_required'))
    return
  }
  // 库存校验只看已勾选的条目
  if (selectedOutOfStock.value.length > 0) {
    ElMessage.warning(t('cart.out_of_stock_title'))
    return
  }
  if (selectedInsufficient.value.length > 0) {
    ElMessage.warning(t('cart.insufficient_stock_title'))
    return
  }
  // 【测试报告#2】收货三件套改走 el-form 校验（含电话格式），
  // 字段级红字由 el-form 自己渲染，这里不再重复 toast。
  // 真正的硬闸门在后端 OrderCreateDTO 的 @Pattern + @Valid：即便前端漏了，
  // shippingPhone="abc" 也会被 400 挡下，不会像报告里那样直接入库。
  try {
    await shippingFormRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    // 【安全修复·P0-1】金额不再由前端计算上传：
    // 后端以 productId 回查 product 表取得单价并自行汇总订单总额，
    // 这里只提交「买什么商品、买几件」+ 收货信息。
    await createOrder({
      shippingName: shippingForm.value.name,
      shippingPhone: shippingForm.value.phone,
      shippingAddress: shippingForm.value.address,
      remark: '',
      items: selectedItems.value.map(item => ({
        productId: item.productId,
        quantity: item.quantity
      }))
    })

    // 【数据丢失修复·2026-09-22】购物车清理的权威收敛到服务端：
    // createOrderWithItems 在同一事务里按本单明细精确移除已购商品，
    // 未勾选 / 未购买的商品保留（淘宝/京东语义）。
    // 原先这里调用 removeSelectedFromCart() 是第二条删除链路：
    //   ① 拦不住服务端 clearCart(userId) 的整单清空 —— 未购商品照样全丢
    //      （本次用户报障的直接根因）；
    //   ② 「按当前勾选删除」在多端/多标签并发时可能误删别处新勾选的商品。
    // 故下单成功后不再发删除请求，只刷新本地列表。

    ElMessage.success(t('cart.submit_success'))
    showOrderConfirm.value = false
    loadCart()
    router.push('/orders')
  } catch (error) {
    // 请求层已统一提示，这里只兜底打印，避免重复弹窗
    console.error('订单提交失败', error)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadCart()
})
</script>

<style scoped>
.cart-page {
  padding: 20px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

:deep(.el-card) {
  background: var(--glass-bg) !important;
  backdrop-filter: blur(var(--glass-blur)) saturate(180%) !important;
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(180%) !important;
  border: 1px solid var(--glass-border) !important;
  border-radius: var(--glass-radius-lg) !important;
  box-shadow:
    0 8px 32px var(--glass-shadow),
    inset 0 1px 0 var(--glass-highlight-soft) !important;
}

:deep(.el-card__header) {
  background: var(--glass-bg) !important;
  border-bottom: 1px solid var(--glass-border) !important;
}

:deep(.el-card__body) {
  background: var(--glass-bg) !important;
}

/* 勾选列：表格首列，紧凑一些不抢视线 */
:deep(.el-table .cell > .el-checkbox) {
  margin-right: 0;
}

.product-info {
  display: flex;
  align-items: center;
  gap: 15px;
}

.product-image {
  width: 80px;
  height: 80px;
  object-fit: cover;
  background: var(--bg-secondary);
  border-radius: 8px;
}

.product-detail {
  flex: 1;
}

.product-name {
  font-size: 14px;
  color: var(--text-primary);
  margin-bottom: 5px;
}

.product-price {
  font-size: 16px;
  font-weight: bold;
  color: #f56c6c;
}

.subtotal {
  font-size: 18px;
  font-weight: bold;
  color: #f56c6c;
}

.empty-cart {
  text-align: center;
  padding: 60px 0;
  color: var(--text-muted);
}

.empty-icon {
  margin-bottom: 16px;
  color: var(--text-muted);
}

.cart-footer {
  margin-top: 20px;
  padding: 20px;
  background: var(--glass-bg);
  backdrop-filter: blur(var(--glass-blur-light)) saturate(160%);
  -webkit-backdrop-filter: blur(var(--glass-blur-light)) saturate(160%);
  border: 1px solid var(--glass-border);
  border-radius: var(--glass-radius-md);
  /* 左侧「全选 + 已选计数」，右侧「金额 + 结算按钮」 */
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.footer-selection {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 4px;
}

.selection-hint {
  font-size: 13px;
  color: var(--text-secondary);
}

.selection-hint b {
  color: var(--accent-color);
  font-size: 15px;
  padding: 0 2px;
}

.footer-actions {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  margin-left: auto;
}

.footer-actions .cart-summary {
  margin-bottom: 0;
}

.coupon-section {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 15px;
}

.coupon-label {
  font-size: 14px;
  color: var(--text-secondary);
}

.coupon-select {
  width: 280px;
}

.cart-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 20px;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  font-size: 14px;
  color: var(--text-secondary);
}

.summary-row.discount span:last-child {
  color: #67c23a;
}

.summary-row.total {
  font-size: 16px;
  font-weight: bold;
  padding-top: 10px;
  border-top: 1px dashed var(--border-color);
}

.total-price {
  font-size: 24px;
  font-weight: bold;
  color: #f56c6c;
}

.order-confirm {
  padding: 10px 0;
}

.confirm-section {
  margin-bottom: 20px;
}

.confirm-section h4 {
  margin-bottom: 10px;
  font-size: 14px;
  color: var(--text-secondary);
}

.confirm-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 14px;
  color: var(--text-primary);
}

.confirm-coupon {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 14px;
  color: #67c23a;
}

.total-section {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 15px;
  border-top: 1px solid var(--border-color);
}

.total-label {
  font-size: 16px;
  font-weight: bold;
  color: var(--text-primary);
}

.total-amount {
  font-size: 24px;
  font-weight: bold;
  color: #f56c6c;
}
</style>
