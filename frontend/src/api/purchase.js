import request from '@/utils/request'

export function getPurchaseOrderList(params) {
  return request({
    url: '/purchase/list',
    method: 'get',
    params
  })
}

export function getPurchaseOrderById(id) {
  return request({
    url: `/purchase/${id}`,
    method: 'get'
  })
}

export function addPurchaseOrder(data) {
  return request({
    url: '/purchase',
    method: 'post',
    data
  })
}

// 【R21 已移除】updatePurchaseOrder / deletePurchaseOrder —— 均为零引用。
// 采购单的修改走「取消后重建」（cancelPurchaseOrder + addPurchaseOrder），
// 后端也未提供 PUT /purchase 与 DELETE /purchase/{id} 的对应语义封装需求。
export function auditPurchaseOrder(id, status, auditRemark) {
  return request({
    url: `/purchase/audit/${id}`,
    method: 'put',
    params: { status, auditRemark }
  })
}

export function completePurchaseOrder(id) {
  return request({
    url: `/purchase/complete/${id}`,
    method: 'put'
  })
}

export function cancelPurchaseOrder(id) {
  return request({
    url: `/purchase/cancel/${id}`,
    method: 'put'
  })
}