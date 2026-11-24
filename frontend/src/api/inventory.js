import request from '@/utils/request'

export function getInventoryList(params) {
  return request({
    url: '/inventory/list',
    method: 'get',
    params
  })
}

export function getWarningInventories() {
  return request({
    url: '/inventory/warning',
    method: 'get'
  })
}

export function getInventoryLogList(params) {
  return request({
    url: '/inventory/log/list',
    method: 'get',
    params
  })
}

export function stockIn(data) {
  return request({
    url: '/inventory/in',
    method: 'post',
    data
  })
}

export function stockOut(data) {
  return request({
    url: '/inventory/out',
    method: 'post',
    data
  })
}

export function syncStock() {
  return request({
    url: '/inventory/sync',
    method: 'post'
  })
}
