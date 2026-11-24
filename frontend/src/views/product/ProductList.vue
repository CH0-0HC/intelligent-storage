<template>
  <div class="product-list-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">{{ t('product.list') }}</span>
          <div class="toolbar">
            <el-input
              v-model="searchKeyword"
              :placeholder="t('product.search_placeholder')"
              style="width: 200px"
              clearable
              @clear="loadProducts"
              @keyup.enter="loadProducts"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <!-- 【测试报告 P3-3】分类下拉原先只改 v-model、不触发查询：选完分类列表纹丝不动，
                 必须再点一次「搜索」才生效，用户极易误判成筛选坏了。
                 这里给它挂上 @change 直接查询；搜索关键字仍保留手动触发（回车/点按钮），
                 避免逐字输入时每敲一个字就打一次接口。clearable 清空同样会触发 change，语义一致。 -->
            <el-select
              v-model="categoryFilter"
              :placeholder="t('product.select_category')"
              clearable
              style="width: 150px"
              @change="loadProducts"
            >
              <el-option :label="t('product.category_food')" :value="1" />
              <el-option :label="t('product.category_digital')" :value="2" />
              <el-option :label="t('product.category_clothing')" :value="3" />
              <el-option :label="t('product.category_home')" :value="4" />
              <el-option :label="t('product.category_fresh')" :value="5" />
            </el-select>
            <el-button type="primary" @click="loadProducts">{{ t('common.search') }}</el-button>
          </div>
        </div>
      </template>
      
      <!-- 【测试报告#12】搜索/筛选无结果时，原先只有一片空网格 + 分页上的一行「共 0 条」，
           既没有插图也没有出路。补一个 el-empty 空态和「清空筛选」入口。 -->
      <el-empty
        v-if="!loading && products.length === 0"
        :description="t('product.no_result')"
      >
        <el-button type="primary" @click="resetFilters">{{ t('common.reset') }}</el-button>
      </el-empty>

      <div v-else class="product-grid">
        <el-card 
          v-for="product in products" 
          :key="product.id" 
          class="product-card"
          shadow="hover"
          @click="goToDetail(product)"
        >
          <img :src="getImageUrl(product.image)" class="product-image" @error="($event.target.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyODAiIGhlaWdodD0iMjAwIiB2aWV3Qm94PSIwIDAgMjgwIDIwMCI+PHJlY3Qgd2lkdGg9IjI4MCIgaGVpZ2h0PSIyMDAiIGZpbGw9IiNmNWY3ZmEiIHJ4PSI0Ii8+PHRleHQgeD0iMTQwIiB5PSIxMDUiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZpbGw9IiM5MDkzOTkiIGZvbnQtc2l6ZT0iMTIiIGZvbnQtZmFtaWx5PSJBcmlhbCI+Tm8gSW1hZ2U8L3RleHQ+PC9zdmc+')" />
          <div class="product-content">
            <h4 class="product-name">{{ product.name }}</h4>
            <p class="product-desc">{{ product.description || t('product.no_description') }}</p>
            <div class="product-tags">
              <el-tag v-if="product.isHot" size="small" type="danger">{{ t('product.hot') }}</el-tag>
              <el-tag v-if="product.isRecommend" size="small" type="success">{{ t('product.recommend') }}</el-tag>
            </div>
            <div class="product-footer">
              <div class="product-price">
                <span class="price-symbol">¥</span>
                <span class="price-value">{{ product.price }}</span>
              </div>
              <div class="product-stock">{{ t('product.stock') }}: {{ product.stock }}</div>
            </div>
            <el-button type="primary" class="add-cart-btn" @click.stop="addToCart(product)">
              {{ t('product.add_to_cart') }}
            </el-button>
          </div>
        </el-card>
      </div>
      
      <div class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[12, 24, 36, 48]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { getProductList } from '@/api/product'
import { addToCart as addToCartApi } from '@/api/cart'
import { getImageUrl } from '@/utils/image'

const router = useRouter()
const { t } = useI18n()

const searchKeyword = ref('')
const categoryFilter = ref('')
const products = ref([])
const currentPage = ref(1)
const pageSize = ref(12)
const total = ref(0)
// 【测试报告#12】首次加载时 products 还是空的，不加 loading 会先闪一下空态
const loading = ref(false)

const loadProducts = async () => {
  loading.value = true
  try {
    const res = await getProductList({
      page: currentPage.value,
      size: pageSize.value,
      keyword: searchKeyword.value,
      categoryId: categoryFilter.value
    })
    products.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (error) {
    console.error('加载商品列表失败', error)
  } finally {
    loading.value = false
  }
}

// 【测试报告#12】空态上的「重置」：清掉关键词与分类筛选再重新拉一次
const resetFilters = () => {
  searchKeyword.value = ''
  categoryFilter.value = ''
  currentPage.value = 1
  loadProducts()
}

const handleSizeChange = () => {
  loadProducts()
}

const handlePageChange = () => {
  loadProducts()
}

/**
 * 点击商品卡片 → 打开商品详情
 *
 * 【修复】原实现只在 CSS 里写了 .product-card { cursor: pointer }，
 * 模板上**没有任何 click 绑定** —— 鼠标指针会变成手型，但点了毫无反应，
 * 而首页（HomeUser.vue）的卡片是绑了 @click 的，于是出现
 * 「首页能点开详情、列表页点不开」的不一致。
 * 这里与首页保持完全一致的跳转方式：router.push(`/products/${id}`)，
 * 对应路由 product/ProductDetail.vue（读取 route.params.id）。
 */
const goToDetail = (product) => {
  router.push(`/products/${product.id}`)
}

const addToCart = async (product) => {
  try {
    await addToCartApi({ productId: product.id, quantity: 1 })
    ElMessage.success(t('product.added_to_cart', { name: product.name, count: 1 }))
  } catch (error) {
    ElMessage.error(t('product.add_to_cart_failed'))
  }
}

onMounted(() => {
  loadProducts()
})
</script>

<style scoped>
.product-list-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
  margin-top: 20px;
}

.product-card {
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  background: var(--glass-bg);
  backdrop-filter: blur(var(--glass-blur)) saturate(180%);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(180%);
  border: 1px solid var(--glass-border);
  border-radius: var(--glass-radius-lg);
  box-shadow:
    0 8px 32px var(--glass-shadow),
    inset 0 1px 0 var(--glass-highlight-soft);
}

.product-card:hover {
  transform: translateY(-5px);
  box-shadow:
    0 16px 48px var(--glass-shadow-hover),
    inset 0 1px 0 var(--glass-highlight);
}

.product-image {
  width: 100%;
  height: 200px;
  object-fit: cover;
  background: var(--bg-secondary);
}

.product-content {
  padding: 15px;
}

.product-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-desc {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-tags {
  display: flex;
  gap: 5px;
  margin-bottom: 10px;
}

.product-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.product-price {
  color: #f56c6c;
}

.price-symbol {
  font-size: 14px;
}

.price-value {
  font-size: 24px;
  font-weight: bold;
}

.product-stock {
  font-size: 13px;
  color: var(--text-muted);
}

.add-cart-btn {
  width: 100%;
}

.pagination {
  margin-top: 30px;
  display: flex;
  justify-content: center;
}
</style>
