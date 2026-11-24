// reset-verify-db.mjs
// 重建「独立验证库」smart_inventory_it —— 供 verify-after-sale.mjs 使用。
//
// 【为什么需要独立验证库】
// 售后验证必须做「时间旅行」（把 order.complete_time 改到 10 天 / 20 天前）
// 和大量订单状态推进，这些操作会污染真实业务数据（老板正在手工测试的那套）。
// 因此验证跑在和真实库同构的克隆库上：表结构与种子数据完全来自权威脚本
// backend/src/main/resources/db/init.sql，只把库名替换掉。
//
// 用法：node scripts/reset-verify-db.mjs
// 可选环境变量：MYSQL_BIN / DB_USERNAME / DB_PASSWORD

import { execFileSync, execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(here, '..')
const SOURCE_SQL = path.join(ROOT, 'backend/src/main/resources/db/init.sql')
const TARGET_DB = process.env.VERIFY_DB || 'smart_inventory_it'
const MYSQL_BIN = process.env.MYSQL_BIN || 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const DB_USER = process.env.DB_USERNAME || 'root'
const DB_PASS = process.env.DB_PASSWORD || '123456'

if (!fs.existsSync(SOURCE_SQL)) {
  console.error(`找不到权威建库脚本: ${SOURCE_SQL}`)
  process.exit(1)
}

const source = fs.readFileSync(SOURCE_SQL, 'utf8')
// 只替换库名，其余（表结构 / 索引 / 种子数据）原样保留，保证与真实库同构
const cloned = source.replaceAll('smart_inventory', TARGET_DB)

const mysql = (args) =>
  execFileSync(MYSQL_BIN, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

// 【必须带 --default-character-set=utf8mb4】
// 否则客户端按默认字符集解析脚本里的中文注释与 DEFAULT '件'，
// 报 `Invalid default value for 'unit'` 并中断建库（本脚本作者踩过）。
mysql(['-u' + DB_USER, '-p' + DB_PASS, '--default-character-set=utf8mb4', '-e', `DROP DATABASE IF EXISTS ${TARGET_DB};`])

// 用临时文件 + 输入重定向导入。
// 【不要改用 execFileSync({ input }) 走管道】：本机 Windows 下会抛
// `spawnSync mysql.exe EBUSY`，脚本直接崩（已实测）。临时文件放在纯 ASCII
// 路径下，规避 cmd 批处理的中文路径编码陷阱。
const tmp = path.join(here, '__init_it.sql')
fs.writeFileSync(tmp, cloned, 'utf8')
try {
  execSync(
    `"${MYSQL_BIN}" -u${DB_USER} -p${DB_PASS} --default-character-set=utf8mb4 < "${tmp}"`,
    { stdio: ['ignore', 'pipe', 'pipe'] }
  )
} finally {
  fs.unlinkSync(tmp)
}

const tables = mysql([
  '-u' + DB_USER, '-p' + DB_PASS, '--default-character-set=utf8mb4', '-N', '-B',
  '-e', `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}';`
]).trim()

const hasAfterSale = mysql([
  '-u' + DB_USER, '-p' + DB_PASS, '--default-character-set=utf8mb4', '-N', '-B',
  '-e', `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_name='after_sale';`
]).trim()

console.log(`验证库 ${TARGET_DB} 已重建：${tables} 张表，after_sale 存在=${hasAfterSale === '1'}`)
if (hasAfterSale !== '1') {
  console.error('after_sale 表缺失，请检查 backend/src/main/resources/db/init.sql 第 22 节')
  process.exit(1)
}
