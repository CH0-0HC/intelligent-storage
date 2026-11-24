<template>
  <div class="product-detail-page">
    <el-card class="detail-card">
      <template #header>
        <div class="page-header">
          <el-button type="primary" link @click="goBack">
            <el-icon><ArrowLeft /></el-icon> {{ t('product.back') }}
          </el-button>
          <span class="page-title">{{ t('product.detail') }}</span>
        </div>
      </template>
      
      <div v-if="product" class="product-detail">
        <div class="product-images">
          <img :src="getImageUrl(product.image)" class="main-image" 
               @error="($event.target.src = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI0MDAiIGhlaWdodD0iNDAwIiB2aWV3Qm94PSIwIDAgNDAwIDQwMCI+PHJlY3Qgd2lkdGg9IjQwMCIgaGVpZ2h0PSI0MDAiIGZpbGw9IiNmNWY3ZmEiIHJ4PSI0Ii8+PHRleHQgeD0iMjAwIiB5PSIyMDUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZpbGw9IiM5MDkzOTkiIGZvbnQtc2l6ZT0iMTQiIGZvbnQtZmFtaWx5PSJBcmlhbCI+Tm8gSW1hZ2U8L3RleHQ+PC9zdmc+')" />
        </div>
        
        <div class="product-info">
          <h1 class="product-name">{{ product.name }}</h1>
          <div class="product-tags">
            <el-tag v-if="product.isHot" type="danger">{{ t('product.hot') }}</el-tag>
            <el-tag v-if="product.isNew" type="success">{{ t('product.new') }}</el-tag>
          </div>
          <div class="product-price">
            <span class="price-symbol">¥</span>
            <span class="price-value">{{ formatPrice(product.price) }}</span>
            <span v-if="product.originalPrice" class="original-price">¥{{ formatPrice(product.originalPrice) }}</span>
          </div>
          <p class="product-desc">{{ product.description || t('product.no_desc') }}</p>

          <!-- ============================================================
               【2026-09-28 新增 · 退货退款规则】
               商家改为「按商品」设置退货期限后，规则必须在**下单前**可见：
               原先全站只有订单详情页一句写死的「确认收货后 15 天内申请售后」，
               与商品实际配置（比如某生鲜商品压根不支持退货）完全脱节。
               这里直接消费后端返回的 product.returnWindowDays / returnNoReason，
               文案口径与售后申请页保持一致（同一套 product.return_rule_* 键）。
               ============================================================ -->
          <div class="return-rule">
            <div class="return-rule-title">
              <el-icon><RefreshLeft /></el-icon>
              <span>{{ t('product.return_rule_title') }}</span>
            </div>
            <div class="return-rule-body">
              <!-- returnWindowDays === 0：商家明确不支持退货，这是最需要提前告知的一档 -->
              <div v-if="isReturnUnsupported" class="rule-line rule-none">
                {{ t('product.return_rule_none') }}
              </div>
              <template v-else>
                <!-- null/undefined 表示商家未配置，按后端默认 15 天展示（见 returnRuleText） -->
                <div class="rule-line">{{ returnRuleText }}</div>
                <!-- 期限支持退货，但商家关掉了无理由：追加一行，避免用户以为还能「不喜欢就退」 -->
                <div v-if="isNoReasonOff" class="rule-line rule-sub">
                  {{ t('product.return_rule_no_reason_off') }}
                </div>
              </template>
            </div>
          </div>

          <div class="product-specs">
            <div class="spec-item">
              <!-- 【2026-10-10 修复】原为「分类ID：1」—— 把主键当展示内容。
                   改为优先显示分类名（后端自第四轮起在商品接口回填 categoryName），
                   回填失败或后端尚未重启时退回 categoryId，不至于显示空。 -->
              <span class="spec-label">{{ t('product.category') }}：</span>
              <span class="spec-value">{{ product.categoryName || product.categoryId || '-' }}</span>
            </div>
            <div class="spec-item">
              <span class="spec-label">{{ t('product.stock') }}：</span>
              <span :class="['spec-value', product.stock < 10 ? 'low-stock' : '']">{{ product.stock }}</span>
            </div>
            <div class="spec-item">
              <span class="spec-label">{{ t('product.sales') }}：</span>
              <span class="spec-value">{{ product.sales }}</span>
            </div>
          </div>
          
          <div class="product-actions">
            <el-input-number v-model="quantity" :min="1" :max="product.stock || 99" />
            <el-button type="primary" @click="addToCart">{{ t('product.add_to_cart') }}</el-button>
            <el-button type="success" @click="buyNow">{{ t('product.buy_now') }}</el-button>
          </div>
        </div>
      </div>
      
      <!-- 【测试报告#9】商品不存在（如 /products/9999）：product 为空时原先一律落到
           el-skeleton —— 骨架屏永远不消失，整页近乎空白，只剩顶部「返回 / 商品详情」。
           这里切成明确的「商品不存在」空态 + 返回入口；加载中仍走骨架屏。 -->
      <div v-else-if="notFound" class="loading-container">
        <el-empty :description="t('product.not_found')">
          <el-button type="primary" @click="goBack">{{ t('common.back') }}</el-button>
        </el-empty>
      </div>
      <div v-else class="loading-container">
        <el-skeleton :rows="4" animated />
      </div>
    </el-card>

    <!-- ============================================================
         【缺陷修复 · 直接购买不生效】
         原 buyNow() 只弹了一句「正在创建订单…」就结束了：
         既不调下单接口、也不跳转，用户等到的永远是同一句提示。
         这里补成完整链路：确认订单弹窗（商品行 + 收货信息 + 合计）
         → POST /order → 跳 /orders?highlight=<新订单id>。
         交互与校验顺序**刻意对齐购物车 Cart.vue 的 submitOrder()**：
         两处是同一个后端接口，用户心智应当一致（先收货人、再电话、最后地址）。
         ============================================================ -->
    <el-dialog v-model="showBuyConfirm" :title="t('product.buy_confirm_title')" width="500px">
      <div v-if="product" class="buy-confirm">
        <div class="confirm-section">
          <h4>{{ t('product.buy_confirm_items') }}</h4>
          <div class="confirm-item">
            <span>{{ product.name }} x {{ quantity }}</span>
            <span>¥{{ confirmSubtotal }}</span>
          </div>
        </div>

        <div class="confirm-section">
          <h4>{{ t('order.shipping_info') }}</h4>
          <!-- 【测试报告#2】与 Cart.vue 同款问题：只有 required 星号、没有 rules/prop，
               电话填 "abc" 一样能下单。两处走同一个后端接口，校验也共用一份。 -->
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
          <span class="total-label">{{ t('order.total_amount') }}</span>
          <span class="total-amount">¥{{ confirmSubtotal }}</span>
        </div>
      </div>

      <template #footer>
        <el-button @click="showBuyConfirm = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submitBuyOrder">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, RefreshLeft } from '@element-plus/icons-vue'
import { getProductById } from '@/api/product'
import { addToCart as addToCartApi } from '@/api/cart'
import { createOrder } from '@/api/order'
import { getToken } from '@/utils/auth'
import { getImageUrl } from '@/utils/image'
import { makeShippingRules } from '@/utils/shippingRules'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const product = ref(null)
// 【测试报告#9】商品不存在 / 加载失败时置位，用于渲染空态而不是永远转骨架屏
const notFound = ref(false)
const quantity = ref(1)

// ==================== 直接购买（确认订单弹窗） ====================
const showBuyConfirm = ref(false)
// 提交期间锁按钮：下单是**非幂等**接口，连点两次会真的生成两张订单
const submitting = ref(false)

// 收货信息与购物车结算同构（字段名保持一致，便于后端订单实体共用一套字段）
const shippingForm = ref({
  name: '',
  phone: '',
  address: ''
})
// 【测试报告#2】收货表单校验；实现见 utils/shippingRules.js（与 Cart.vue 共用）
const shippingFormRef = ref(null)
const shippingRules = makeShippingRules(t)

const goBack = () => {
  router.push('/home')
}

// ==================== 退货退款规则展示 ====================
// 口径与后端 AfterSaleRule 默认值严格一致：null/undefined = 商家未配置 = 15 天。
// 之所以把 null 当成 15 而不是当成 0：后端也把「未配置」按默认 15 天判资格，
// 前端若显示成「不支持退货」会和实际可申请状态自相矛盾。
const isReturnUnsupported = computed(() => Number(product.value?.returnWindowDays) === 0)

const isNoReasonOff = computed(() => Number(product.value?.returnNoReason) === 0)

const returnRuleText = computed(() => {
  const raw = product.value?.returnWindowDays
  // 未配置（null/undefined）走独立的 default_days 文案，不用 {days} 传参，
  // 免得将来默认天数调整时出现「文案里 15、参数 20」这类对不上的情况
  if (raw === null || raw === undefined || raw === '') {
    return t('product.return_rule_default_days')
  }
  return t('product.return_rule_days', { days: raw })
})

// 合计只用于**展示**；真实金额由后端按 productId 回查单价后自行汇总
const confirmSubtotal = computed(() => {
  const price = Number(product.value?.price) || 0
  return (price * quantity.value).toFixed(2)
})

const addToCart = async () => {
  try {
    await addToCartApi({ productId: product.value.id, quantity: quantity.value })
    ElMessage.success(t('product.added_to_cart', { name: product.value.name, count: quantity.value }))
  } catch (error) {
    ElMessage.error(t('product.add_to_cart_failed'))
  }
}

/**
 * 「直接购买」入口。
 *
 * 【为什么要先查 token】下单接口 requiresAuth，未登录直接点会先收到 401 弹窗
 * 再被踢到登录页，用户看到的是一次「莫名其妙的报错」。这里在**发请求前**拦截，
 * 给出一句明确指引（login_required）再跳登录页。
 */
const buyNow = () => {
  if (!getToken()) {
    ElMessage.warning(t('product.login_required'))
    router.push('/login')
    return
  }
  showBuyConfirm.value = true
  // 上次提交留下的红字不要带到新的一次填写里
  shippingFormRef.value?.clearValidate()
}

/**
 * 提交直接购买订单。
 *
 * 校验顺序与 Cart.vue 的 submitOrder() 完全一致（收货人 → 电话 → 地址），
 * 同样是逐项 return，而不是一次性把所有错误摆出来 —— 保持两处交互同感。
 */
const submitBuyOrder = async () => {
  // 【测试报告#2】收货三件套改走 el-form 校验（含电话格式），
  // 字段级红字由 el-form 渲染，这里不再重复 toast。原先三段 if 拦不住 "abc"。
  try {
    await shippingFormRef.value?.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    // 【安全口径】金额一律由服务端按 productId 回查计算，
    // 前端只提交「买什么、买几件」+ 收货信息，**不传任何金额字段**。
    const res = await createOrder({
      shippingName: shippingForm.value.name,
      shippingPhone: shippingForm.value.phone,
      shippingAddress: shippingForm.value.address,
      remark: '',
      items: [{ productId: product.value.id, quantity: quantity.value }]
    })

    ElMessage.success(t('product.order_created'))
    showBuyConfirm.value = false

    // 带上 highlight 让订单列表把刚创建的订单滚动到可视区：
    // 否则新订单可能落在第二页，用户回到列表只会觉得「下单好像没成功」。
    const newOrderId = res?.data?.id
    if (newOrderId) {
      router.push(`/orders?highlight=${newOrderId}`)
    } else {
      // 接口没回 id（老后端/字段缺失）时退化为普通跳转，不阻断主流程
      router.push('/orders')
    }
  } catch (error) {
    // 【不重复弹窗】@/utils/request 的响应拦截器已统一提示过错误，
    // 这里再 ElMessage.error 会变成一串重复报错。只在控制台留痕便于排查。
    console.error('直接购买下单失败', error)
  } finally {
    submitting.value = false
  }
}

const formatPrice = (price) => {
  return parseFloat(price).toFixed(2)
}

const loadProduct = async () => {
  notFound.value = false
  try {
    const productId = route.params.id
    const res = await getProductById(productId)
    // 后端对不存在的商品可能是 200 + 空 data，也可能是 400 走 catch：
    // 两种都必须落到空态，否则页面会一直停在骨架屏上
    if (!res.data || res.data.id == null) {
      notFound.value = true
      product.value = null
      return
    }
    product.value = res.data
  } catch (error) {
    notFound.value = true
    product.value = null
    console.error('加载商品详情失败', error)
    ElMessage.error(t('product.detail_load_failed'))
  }
}

onMounted(() => {
  loadProduct()
})
</script>

<style scoped>
.product-detail-page {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 20px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.detail-card {
  max-width: 900px;
  margin: 0 auto;
}

.product-detail {
  display: grid;
  grid-template-columns: 400px 1fr;
  gap: 40px;
  padding: 20px 0;
}

.product-images {
  grid-column: 1;
  /* 网格子项默认 min-width:auto —— 不允许收缩到内容以下，窄屏下会把整列撑爆 */
  min-width: 0;
}

.main-image {
  width: 100%;
  height: 400px;
  object-fit: cover;
  background: var(--bg-secondary);
  border-radius: 8px;
}

.product-info {
  grid-column: 2;
  min-width: 0;
}

.product-name {
  font-size: 24px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 15px;
}

.product-tags {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.product-price {
  margin-bottom: 20px;
}

.price-symbol {
  font-size: 20px;
  color: #f56c6c;
}

.price-value {
  font-size: 36px;
  font-weight: bold;
  color: #f56c6c;
}

.original-price {
  font-size: 16px;
  color: var(--text-muted);
  text-decoration: line-through;
  margin-left: 15px;
}

.product-desc {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.8;
  margin-bottom: 30px;
}

.product-specs {
  background: var(--bg-secondary);
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 30px;
}

.spec-item {
  display: flex;
  margin-bottom: 10px;
}

.spec-item:last-child {
  margin-bottom: 0;
}

.spec-label {
  color: var(--text-muted);
  min-width: 80px;
}

.spec-value {
  color: var(--text-primary);
  font-weight: 500;
}

.spec-value.low-stock {
  color: #f56c6c;
}

.product-actions {
  display: flex;
  gap: 15px;
  align-items: center;
}

.product-actions :deep(.el-input-number) {
  width: 120px;
}

.loading-container {
  padding: 40px;
}

/* ==================== 退货退款规则（下单前可见） ==================== */
.return-rule {
  background: var(--bg-secondary);
  border-left: 3px solid var(--accent-color, #409eff);
  border-radius: 6px;
  padding: 12px 16px;
  margin-bottom: 20px;
}

.return-rule-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 6px;
}

.rule-line {
  font-size: 13px;
  color: var(--text-primary);
  line-height: 1.7;
}

/* 「不支持退货」是需要用户特别注意的一档，用告警色区分 */
.rule-none {
  color: #e6a23c;
}

/* 无理由被商家关闭时的补充说明：弱化处理，不抢主文案视线 */
.rule-sub {
  color: var(--text-muted);
}

/* ==================== 直接购买 · 确认订单弹窗 ==================== */
/* 结构样式与 Cart.vue 的确认弹窗保持一致，用户在两处看到的是同一种结算界面 */
.buy-confirm {
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

@media (max-width: 768px) {
  .product-detail {
    grid-template-columns: 1fr;
    gap: 20px;
  }

  /* ============================================================
     【2026-10-10 修复 · 移动端商品详情布局】
     只把 grid-template-columns 改成 1fr 是不够的：基础样式里
     `.product-images{grid-column:1}` 与 `.product-info{grid-column:2}`
     仍然生效，而单列布局下**没有第 2 列** —— 网格会临时创建一个隐式列，
     其宽度由 product-info 的内容决定（390px 实测约 372px）。
     于是第一列 1fr 被压到 0：
       · .product-images rect = 0x492、<img> 渲染宽 0x250 → 主图完全不显示
         （图片本身已加载 natural=1858x1900，是布局问题不是资源问题）
       · .el-card__body 出现横向滚动条（scrollW 392 > clientW 332）
     两条修复缺一不可：grid-column 复位去掉隐式列，min-width:0 允许收缩。
     ============================================================ */
  .product-images,
  .product-info {
    grid-column: auto;
    min-width: 0;
  }

  .main-image {
    height: 250px;
  }

  /* 数量框(固定120px) + 两个按钮 + 两个 15px 间距在 286px 内放不下，
     390px 实测「立即购买」按钮越出 card-body 10px 并撑出横向滚动条。
     窄屏改为：数量框占满一行，两个按钮换行排在其下。 */
  .product-actions {
    flex-wrap: wrap;
    gap: 10px;
  }

  .product-actions :deep(.el-input-number) {
    width: 100%;
  }

  /* 触达尺寸：Element Plus 默认按钮高 32px，窄屏单手操作偏小
     （390px 实测本页 8 个按钮高度均 < 40px）。主操作按钮提到 44px
     —— 移动端可点击目标的通用推荐值，只在窄屏生效，桌面端不受影响。 */
  .product-actions :deep(.el-button) {
    min-height: 44px;
  }

  /* 「返回」是 link 型按钮，默认只有 20px 高，比主按钮还难点；
     顶栏返回是窄屏下最常见的回退动作，同样提到 40px。 */
  .page-header :deep(.el-button) {
    min-height: 40px;
  }

  .product-name {
    font-size: 20px;
    word-break: break-word;
  }

  /* 窄屏下这些块的内容决定最小宽度，允许折行而不是把容器撑开 */
  .return-rule,
  .product-desc,
  .spec-value {
    overflow-wrap: anywhere;
    word-break: break-word;
  }
}
</style>