// apply-item-scope-migration.mjs —— 幂等升级 P1-6 所需的 schema
//
// 【它做什么】给「已经建好的库」补上两样东西（缺哪补哪，都齐了就什么都不做）：
//   ① after_sale.item_scope 列
//   ② after_sale_item 表（DDL 与 backend/src/main/resources/db/after_sale_item.sql 同源）
//
// 【为什么必须有它，而不是直接让人跑 after_sale.sql】
// db/after_sale.sql 末尾的 ALTER 是「人工升级参考」的**裸语句**：
//   · 新库由 init.sql 的 CREATE TABLE after_sale 建成时**已含** item_scope 列；
//   · MySQL 8 **不支持** `ADD COLUMN IF NOT EXISTS`；
//   ⇒ 在新库上再跑一次那条 ALTER 会直接报 `ERROR 1060 Duplicate column name`，
//     把「照着文档执行升级」的人卡住。本脚本先查 information_schema 再决定是否执行，
//     **可重复运行**（第二次全部打印「已存在，跳过」）。
//
// 【用法】
//   node scripts/apply-item-scope-migration.mjs                    # 默认升级 smart_inventory
//   node scripts/apply-item-scope-migration.mjs --db smart_inventory_it
//   node scripts/apply-item-scope-migration.mjs --db smart_inventory,smart_inventory_it
//   node scripts/apply-item-scope-migration.mjs --print-sql        # 只打印将执行的 SQL，不连库
//
// 【环境变量】MYSQL_BIN / DB_USERNAME / DB_PASSWORD（与 reset-verify-db.mjs 同一口径）
//
// 退出码：0 = 全部目标已就绪（含「本来就有、跳过」）；1 = 出现硬错误（缺表/执行失败/连不上）

import { execFileSync, execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(here, '..')

const MYSQL_BIN = process.env.MYSQL_BIN || 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const DB_USER = process.env.DB_USERNAME || 'root'
const DB_PASS = process.env.DB_PASSWORD || '123456'

const DDL_FILE = path.join(ROOT, 'backend/src/main/resources/db/after_sale_item.sql')

/** after_sale 加列语句 —— 与 init.sql 第 22 节的列定义**逐字对齐** */
const ALTER_ADD_ITEM_SCOPE =
  'ALTER TABLE `after_sale`\n' +
  "    ADD COLUMN `item_scope` TINYINT NOT NULL DEFAULT 0\n" +
  "        COMMENT '售后范围: 0-整单（历史数据/缺省，兼容旧语义）, 1-按商品（after_sale_item 有明细）'\n" +
  '        AFTER `refund_amount`;'

// ==================== 参数解析 ====================
const args = process.argv.slice(2)
const PRINT_SQL = args.includes('--print-sql')
const dbArgIdx = args.findIndex((a) => a === '--db' || a.startsWith('--db='))
let TARGETS = ['smart_inventory']
if (dbArgIdx >= 0) {
  const raw = args[dbArgIdx].includes('=')
    ? args[dbArgIdx].split('=')[1]
    : args[dbArgIdx + 1]
  if (raw) {
    TARGETS = raw.split(',').map((s) => s.trim()).filter(Boolean)
  }
}

// ==================== after_sale_item 的 DDL（从权威文件读取，去掉 USE 行）====================
function loadCreateTableSql() {
  if (!fs.existsSync(DDL_FILE)) {
    console.error(`找不到权威 DDL 文件：${DDL_FILE}`)
    process.exit(1)
  }
  const raw = fs.readFileSync(DDL_FILE, 'utf8')
  // 去掉 `USE xxx;`（库由 -D 参数指定，避免误写到别的库），保留其余语句与注释
  const withoutUse = raw
    .split('\n')
    .filter((line) => !/^\s*USE\s+/i.test(line))
    .join('\n')
    .trim()
  if (!/CREATE TABLE/i.test(withoutUse)) {
    console.error(`DDL 文件里没有 CREATE TABLE，请检查：${DDL_FILE}`)
    process.exit(1)
  }
  return withoutUse
}

const CREATE_TABLE_SQL = loadCreateTableSql()

// ==================== --print-sql：不连库，只输出将执行的 SQL ====================
if (PRINT_SQL) {
  console.log('-- 目标库：' + TARGETS.join(', ') + '（每个库各执行下面两段，缺哪补哪）')
  console.log('')
  console.log('-- ① after_sale.item_scope（仅当列不存在时执行）')
  console.log(ALTER_ADD_ITEM_SCOPE)
  console.log('')
  console.log('-- ② after_sale_item（仅当表不存在时执行；来源：backend/src/main/resources/db/after_sale_item.sql）')
  console.log(CREATE_TABLE_SQL)
  console.log('')
  console.log('-- 幂等由 information_schema 判定保证；本模式未连接数据库、未做任何修改。')
  process.exit(0)
}

// ==================== 连库辅助 ====================
const mysql = (extraArgs) =>
  execFileSync(MYSQL_BIN, extraArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

const scalar = (sql) =>
  mysql([
    '-u' + DB_USER, '-p' + DB_PASS, '--default-character-set=utf8mb4',
    '-N', '-B', '-e', sql
  ]).replace(/\r/g, '').trim()

/** 执行一段多语句 SQL：走临时文件 + 输入重定向
 *  【为什么不用 execFileSync({input})】本机 Windows 下走管道会抛 spawnSync mysql.exe EBUSY
 *  （reset-verify-db.mjs 已实测），临时文件放纯 ASCII 路径规避 cmd 中文路径编码陷阱。 */
function execSqlText(sqlText, db) {
  const tmp = path.join(here, '__migrate_item_scope.sql')
  fs.writeFileSync(tmp, sqlText, 'utf8')
  try {
    execSync(
      `"${MYSQL_BIN}" -u${DB_USER} -p${DB_PASS} --default-character-set=utf8mb4 -D ${db} < "${tmp}"`,
      { stdio: ['ignore', 'pipe', 'pipe'] }
    )
    return true
  } finally {
    try { fs.unlinkSync(tmp) } catch { /* ignore */ }
  }
}

// ==================== 逐库执行 ====================
let hardFail = 0

for (const db of TARGETS) {
  console.log('')
  console.log(`===== 目标库：${db} =====`)

  // 0) 库必须存在；不存在则跳过（不视为失败：可能只是还没建）
  const dbExists = scalar(
    `SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${db}';`
  )
  if (dbExists !== '1') {
    console.log(`SKIP  库 ${db} 不存在（先建库或换 --db），未做任何修改`)
    continue
  }

  // 1) after_sale 表必须在（否则谈不上升级）
  const hasAfterSale = scalar(
    `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_name='after_sale';`
  )
  if (hasAfterSale !== '1') {
    console.log(`FAIL  库 ${db} 里没有 after_sale 表 —— 请先用 db/init.sql 建库`)
    hardFail++
    continue
  }

  // 2) item_scope 列：缺则加
  const hasColumn = scalar(
    `SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${db}' ` +
    `AND table_name='after_sale' AND column_name='item_scope';`
  )
  if (hasColumn === '1') {
    console.log('OK    after_sale.item_scope 已存在，跳过加列')
  } else {
    try {
      execSqlText(ALTER_ADD_ITEM_SCOPE, db)
      const now = scalar(
        `SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${db}' ` +
        `AND table_name='after_sale' AND column_name='item_scope';`
      )
      if (now === '1') {
        console.log('DONE  已为 after_sale 添加 item_scope 列')
      } else {
        console.log('FAIL  加列后回读不到 item_scope，请人工确认')
        hardFail++
      }
    } catch (e) {
      console.log('FAIL  加列失败：' + (e.stderr || e.message))
      hardFail++
    }
  }

  // 3) after_sale_item 表：缺则建
  const hasTable = scalar(
    `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_name='after_sale_item';`
  )
  if (hasTable === '1') {
    console.log('OK    after_sale_item 表已存在，跳过建表')
  } else {
    try {
      execSqlText(CREATE_TABLE_SQL, db)
      const now = scalar(
        `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_name='after_sale_item';`
      )
      const idx = scalar(
        `SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='${db}' AND table_name='after_sale_item';`
      )
      if (now === '1') {
        console.log(`DONE  已创建 after_sale_item 表（索引条目 ${idx} 项，PK + 4 个索引应为 7）`)
      } else {
        console.log('FAIL  建表后回读不到 after_sale_item，请人工确认')
        hardFail++
      }
    } catch (e) {
      console.log('FAIL  建表失败：' + (e.stderr || e.message))
      hardFail++
    }
  }
}

console.log('')
console.log(hardFail === 0
  ? '===== 结果：所有目标库均已就绪（含「本来就有、跳过」）====='
  : `===== 结果：有 ${hardFail} 项硬失败，请查看上面的 FAIL 行 =====`)
process.exit(hardFail === 0 ? 0 : 1)
