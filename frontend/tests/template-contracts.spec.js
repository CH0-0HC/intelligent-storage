// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 模板层契约（2026-10-11 · 测试报告 P2-1 / P3-3）
 *
 * 这两类问题的共同点是：**跑起来才看得见**（一个在控制台刷告警、一个要人手点两次搜索），
 * 单靠运行时断言很难稳定复现。所以这里直接对源码立规矩 —— 谁把写法改回去，测试立刻变红。
 *
 * 之所以敢用源码断言：这两条本来就是**静态 API 契约**（Element Plus 的 props 名、
 * 一个事件绑定的有无），不依赖运行时状态。
 */
const SRC = fileURLToPath(new URL('../src/', import.meta.url))

const read = (relPath) => readFileSync(join(SRC, relPath), 'utf8')

/** 递归列出 src 下所有 .vue */
function listVue(dir) {
  const out = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) out.push(...listVue(full))
    else if (entry.name.endsWith('.vue')) out.push(full)
  }
  return out
}

/** 抽出模板里所有 <el-radio / <el-radio-button 起始标签（单行即可，本项目未跨行写） */
function radioTags(template) {
  return (template.match(/<el-radio(?:-button)?\b[^>]*>/g) || []).filter(
    (tag) => !tag.startsWith('<el-radio-group')
  )
}

describe('P2-1 · Element Plus 单选组件不得再用弃用的 label 当值', () => {
  it('全项目 .vue 里不存在 el-radio / el-radio-button 的 label 属性', () => {
    const offenders = []
    for (const file of listVue(SRC)) {
      for (const tag of radioTags(readFileSync(file, 'utf8'))) {
        if (/(^|\s):?label\s*=/.test(tag)) {
          offenders.push(`${file.replace(SRC, '')}: ${tag}`)
        }
      }
    }
    // 报告原文：EP 2.14 只警告，未来大版本会把 label 当「显示文本」，
    // 届时这些单选框会静默失效（选中值错乱）—— 这条断言就是防那一天。
    expect(offenders, `以下单选组件仍在用弃用的 label 属性：\n${offenders.join('\n')}`)
      .toEqual([])
  })

  it('AI 分析看板 8 个 el-radio-button 全部改成了 value，且值与 ref 默认值对得上', () => {
    const tpl = read('views/merchant/AIDashboard.vue')
    const tags = radioTags(tpl)
    expect(tags.length, '看板应有 8 个 el-radio-button').toBe(8)

    const values = tags.map((tag) => (tag.match(/value="([^"]+)"/) || [])[1])
    expect(values.filter(Boolean).length, '每个 el-radio-button 都必须有 value=').toBe(8)

    // 值集合必须与脚本里的 ref 初值一致，否则选中态会对不上
    expect(values.sort()).toEqual(
      ['bar', 'daily', 'line', 'month', 'monthly', 'quarter', 'week', 'weekly'].sort()
    )
    for (const key of ['trendDimension', 'trendChartType', 'reportPeriod']) {
      expect(tpl, `必须保留 ${key} 的 ref 声明`).toMatch(new RegExp(`${key}\\s*=\\s*ref\\(`))
    }
  })

  it('采购单审核的两个 el-radio 用 :value（数字 1/2，不是 label）', () => {
    const tags = radioTags(read('views/merchant/PurchaseManage.vue'))
    expect(tags.length).toBe(2)
    for (const tag of tags) {
      expect(tag).toMatch(/:value="[12]"/)
      expect(tag).not.toMatch(/:?label\s*=/)
    }
    // auditResult 的取值口径：1=通过 2=拒绝（后端再映射成 1/3）
    expect(read('views/merchant/PurchaseManage.vue')).toMatch(/auditResult:\s*1/)
  })

  it('对照组：项目里已有的正确写法仍被用着（AfterSaleApply / KefuTicketDetail）', () => {
    const apply = radioTags(read('views/afterSale/AfterSaleApply.vue'))
    const kefu = radioTags(read('views/kefu/KefuTicketDetail.vue'))
    // 长度先钉死，否则空数组的 every() 会假通过
    expect(apply.length).toBe(3)
    expect(kefu.length).toBe(2)
    expect(apply.every((t) => /:value="/.test(t))).toBe(true)
    expect(kefu.every((t) => /:value="/.test(t))).toBe(true)
  })
})

describe('P3-3 · 分类下拉必须选中即查询', () => {
  it('ProductList 的分类 el-select 挂了 @change="loadProducts"', () => {
    const tpl = read('views/product/ProductList.vue')
    // 只取 el-select 的**起始标签**（属性里没有 >，[^>] 可跨行），避免被 5 个 el-option 撑爆
    const match = tpl.match(/<el-select[^>]*>/)
    expect(match, 'ProductList 应存在分类 el-select').toBeTruthy()
    const tag = match[0]
    expect(tag, '分类下拉必须选中即查询（否则要再点一次搜索，报告 P3-3）')
      .toMatch(/@change="loadProducts"/)
    expect(tag).toMatch(/categoryFilter/)
    expect(tag).toMatch(/clearable/)
  })

  it('搜索关键字仍保留手动触发（回车 / 点搜索），不改成逐字请求', () => {
    const tpl = read('views/product/ProductList.vue')
    expect(tpl).toMatch(/@keyup\.enter="loadProducts"/)
    expect(tpl).toMatch(/<el-button[^>]*@click="loadProducts"/)
  })
})
