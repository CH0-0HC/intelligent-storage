// 订单 / 采购单状态展示的唯一数据源。
//
// 此前 OrderList.vue、OrderManage.vue、PurchaseManage.vue 各自硬编码了一份
// 「状态 → i18n key / el-tag 颜色」映射，三处颜色不一致（典型：状态 4「已取消」
// 在订单管理里是 danger、在其它两页是 info）。集中到这里后，任何配色调整只改一处。
//
// 约定：
//   · text 存 i18n key（不存文案），由页面用 t() 解析，保证切换语言时同步刷新；
//   · type 为 Element Plus el-tag 的 type 值。

// 订单状态（order.orderStatus）：0-待付款 1-已付款 2-已发货 3-已完成 4-已取消
export const ORDER_STATUS_MAP = {
  0: { text: 'order.status_pending', type: 'warning' },
  1: { text: 'order.status_paid', type: 'info' },
  2: { text: 'order.status_shipped', type: 'primary' },
  3: { text: 'order.status_completed', type: 'success' },
  4: { text: 'order.status_canceled', type: 'info' }
}

// 采购单状态（purchaseOrder.status）：0-待审核 1-已审核 2-已完成 3-审核不通过 4-已取消
export const PURCHASE_STATUS_MAP = {
  0: { text: 'purchase.status_pending', type: 'warning' },
  1: { text: 'purchase_manage.audit_approved', type: 'success' },
  2: { text: 'order.status_completed', type: 'info' },
  3: { text: 'purchase_manage.audit_rejected', type: 'danger' },
  4: { text: 'purchase.status_canceled', type: 'info' }
}

// 取订单状态的 i18n key（缺失时回退 order.unknown）
export const getOrderStatusText = (status) =>
  ORDER_STATUS_MAP[status]?.text || 'order.unknown'

// 取订单状态的 el-tag type（缺失时回退 info）
export const getOrderStatusType = (status) =>
  ORDER_STATUS_MAP[status]?.type || 'info'

// 取采购单状态的 i18n key（缺失时回退 order.unknown）
export const getPurchaseStatusText = (status) =>
  PURCHASE_STATUS_MAP[status]?.text || 'order.unknown'

// 取采购单状态的 el-tag type（缺失时回退 info）
export const getPurchaseStatusType = (status) =>
  PURCHASE_STATUS_MAP[status]?.type || 'info'
