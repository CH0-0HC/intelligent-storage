import request from '@/utils/request'

export function getCartList() {
  return request({
    url: '/cart/list',
    method: 'get'
  })
}

export function addToCart(data) {
  return request({
    url: '/cart/add',
    method: 'post',
    data
  })
}

export function updateCartQuantity(id, data) {
  return request({
    url: `/cart/update/${id}`,
    method: 'put',
    data
  })
}

export function removeFromCart(id) {
  return request({
    url: `/cart/remove/${id}`,
    method: 'delete'
  })
}

export function clearCart() {
  return request({
    url: '/cart/clear',
    method: 'delete'
  })
}

// ==================== 勾选（2026-09-22 新增） ====================
// 勾选状态存在服务端 cart.selected 列，所以刷新页面/重新登录后仍会保持。

/** 勾选 / 取消勾选单件商品。selected: 1-选中，0-未选中 */
export function updateCartSelected(id, selected) {
  return request({
    url: `/cart/selected/${id}`,
    method: 'put',
    data: { selected }
  })
}

/** 全选 / 取消全选。selected: 1-全选，0-取消全选 */
export function updateAllCartSelected(selected) {
  return request({
    url: '/cart/selected-all',
    method: 'put',
    data: { selected }
  })
}

/**
 * 移除「已勾选」的商品（部分结算成功后调用）。
 * 注意不要用 clearCart：那会把未勾选的商品一起删掉。
 */
export function removeSelectedFromCart() {
  return request({
    url: '/cart/remove-selected',
    method: 'delete'
  })
}