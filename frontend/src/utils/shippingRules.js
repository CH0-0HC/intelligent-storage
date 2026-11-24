/**
 * 收货信息表单校验（测试报告#2）。
 *
 * 【修的是什么】购物车「结算」和商品详情「立即购买」两个弹窗，
 * 表单上只挂了个 `required` 星号、没有任何 el-form rules —— 收货人电话填 `abc`
 * 照样能下单，`shipping_phone="abc"` 直接入库并在商家订单列表里展示。
 * 两个弹窗走的是同一个后端下单接口，校验口径也必须同一份，故收敛到这里共用。
 *
 * 【为什么用 validator 而不是静态 message】validator 在**触发时**才调用 t()，
 * 切换语言后提示文案会跟着变；静态 `message: t(...)` 在构造 rules 那一刻就固定死了，
 * 会留下「界面是英文、校验提示是中文」的坑。
 *
 * 【为什么没有 el-form-item.prop 就等于没校验】Element Plus 靠 prop 把
 * el-form-item 关联到 rules 里的字段名，少了 prop 规则根本不会挂上去 ——
 * 这也是原先「字段带 * 却完全不校验」的原因之一。
 */

export { PHONE_PATTERN } from './phonePattern'

import { PHONE_PATTERN } from './phonePattern'

/**
 * 生成收货表单 rules。
 *
 * @param {(key: string) => string} t vue-i18n 的翻译函数，调用时传当前作用域的 t
 * @returns {{ name: Array, phone: Array, address: Array }}
 */
export function makeShippingRules(t) {
  const required = (messageKey) => (rule, value, callback) => {
    if (String(value ?? '').trim()) {
      callback()
    } else {
      callback(new Error(t(messageKey)))
    }
  }

  const phoneFormat = (rule, value, callback) => {
    const v = String(value ?? '').trim()
    // 空值交给上面的必填规则报错，这里只管「填了但不合法」
    if (!v || PHONE_PATTERN.test(v)) {
      callback()
    } else {
      callback(new Error(t('order.shipping_phone_invalid')))
    }
  }

  return {
    name: [{ validator: required('order.shipping_name_placeholder'), trigger: 'blur' }],
    phone: [
      { validator: required('order.shipping_phone_placeholder'), trigger: 'blur' },
      { validator: phoneFormat, trigger: 'blur' }
    ],
    address: [{ validator: required('order.shipping_address_placeholder'), trigger: 'blur' }]
  }
}
