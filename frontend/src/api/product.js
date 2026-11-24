import request from '@/utils/request'

export function getProductList(params) {
  return request({
    url: '/product/list',
    method: 'get',
    params
  })
}

export function getProductById(id) {
  return request({
    url: `/product/${id}`,
    method: 'get'
  })
}

export function getHotProducts(limit = 10) {
  return request({
    url: '/product/hot',
    method: 'get',
    params: { limit }
  })
}

// 【R21 已移除】getRecommendProducts / getProductsByCategory —— 均为零引用。
// 个性化推荐走 /ai/recommend（AiController），分类筛选走 /product/list?categoryId=，
// 这两个 REST 封装没有任何调用点。
export function addProduct(data) {
  return request({
    url: '/product',
    method: 'post',
    data
  })
}

export function updateProduct(data) {
  return request({
    url: '/product',
    method: 'put',
    data
  })
}

export function deleteProduct(id) {
  return request({
    url: `/product/${id}`,
    method: 'delete'
  })
}

export function updateProductStatus(id, status) {
  return request({
    url: `/product/status/${id}`,
    method: 'put',
    params: { status }
  })
}
