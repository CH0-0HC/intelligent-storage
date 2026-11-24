import request from '@/utils/request'

export function getOrderList(params) {
  return request({
    url: '/order/list',
    method: 'get',
    params
  })
}

export function getOrderById(id) {
  return request({
    url: `/order/${id}`,
    method: 'get'
  })
}

export function createOrder(data) {
  return request({
    url: '/order',
    method: 'post',
    data
  })
}

export function updateOrderStatus(id, status) {
  return request({
    url: `/order/status/${id}`,
    method: 'put',
    params: { status }
  })
}

// 【R21 已移除】deleteOrder(id) —— 全项目零引用。
// 订单不提供物理删除（取消统走 PUT /order/status/{id}?status=4），故该封装无使用场景。
export function shipOrder(id, data) {
  return request({
    url: `/order/ship/${id}`,
    method: 'put',
    data
  })
}