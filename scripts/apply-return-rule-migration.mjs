// apply-return-rule-migration.mjs —— 幂等升级「商品级退货规则 + 客服裁定工单」所需的 schema
//
// 【它做什么】给「已经建好的库」补上三样东西（缺哪补哪，都齐了就什么都不做）：
//   ① product.return_window_days 列（该商品支持的退货退款期限，天）
//   ② product.return_no_reason 列（是否支持无理由退货）
//   ③ after_sale.cs_ticket_id 列（超期放行的可追溯凭证）
//   ④ service_ticket 表（客服裁定工单）
//
// 【为什么必须有它，而不是直接让人跑 service_ticket.sql】
// 与 apply-item-scope-migration.mjs 同一理由：MySQL 8 **不支持**
// `ADD COLUMN IF NOT EXISTS`，把裸 ALTER 交给使用者必然在新库上报
// `ERROR 1060 Duplicate column name`。本脚本先查 information_schema 再决定
// 是否执行，**可重复运行**（第二次全部打印「已存在，跳过」）。
//
// 【默认值为什么是 15 / 1】
// 存量商品的 return_window_days 直接取 15、return_no_reason 取 1，
// 正好等于改造前 AfterSaleRule.WINDOW_DAYS=15 与「支持无理由」的旧口径 ——
// 即**升级不改变任何存量商品的行为**，只有商家主动改过才会生效。
//
// 【用法】
//   node scripts/apply-return-rule-migration.mjs                    # 默认升级 smart_inventory
//   node scripts/apply-return-rule-migration.mjs --db smart_inventory,smart_inventory_it
//   node scripts/apply-return-rule-migration.mjs --print-sql        # 只打印将执行的 SQL，不连库
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

const TICKET_DDL_FILE = path.join(ROOT, 'backend/src/main/resources/db/service_ticket.sql')

/** product 加列 —— 与 init.sql 第 3 节的列定义**逐字对齐** */
const ALTER_ADD_RETURN_WINDOW =
  'ALTER TABLE `product`\n' +
  "    ADD COLUMN `return_window_days` INT NOT NULL DEFAULT 15\n" +
  "        COMMENT '退货退款期限(天)，0-不支持退货退款；默认15'\n" +
  '        AFTER `is_recommend`;'

const ALTER_ADD_RETURN_NO_REASON =
  'ALTER TABLE `product`\n' +
  "    ADD COLUMN `return_no_reason` TINYINT NOT NULL DEFAULT 1\n" +
  "        COMMENT '是否支持无理由退货: 0-不支持, 1-支持'\n" +
  '        AFTER `return_window_days`;'

/** after_sale 加列 —— 与 init.sql 第 22 节的列定义**逐字对齐** */
const ALTER_ADD_CS_TICKET =
  'ALTER TABLE `after_sale`\n' +
  "    ADD COLUMN `cs_ticket_id` BIGINT DEFAULT NULL\n" +
  "        COMMENT '客服裁定工单ID（超期放行凭证）；NULL-常规时效内申请'\n" +
  '        AFTER `item_scope`;'

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

// ==================== service_ticket 的 DDL（从权威文件读取）====================
// service_ticket.sql 里同时含「升级已有库」的 PREPARE 语句，这里只取 CREATE TABLE
// 那一段（到第一个分号结束），避免把升级段落重复执行一遍。
function loadCreateTableSql() {
  if (!fs.existsSync(TICKET_DDL_FILE)) {
    console.error(`找不到权威 DDL 文件：${TICKET_DDL_FILE}`)
    process.exit(1)
  }
  const raw = fs.readFileSync(TICKET_DDL_FILE, 'utf8')
  const withoutUse = raw
    .split('\n')
    .filter((line) => !/^\s*USE\s+/i.test(line))
    .join('\n')
  const start = withoutUse.search(/CREATE\s+TABLE/i)
  if (start < 0) {
    console.error(`DDL 文件里没有 CREATE TABLE，请检查：${TICKET_DDL_FILE}`)
    process.exit(1)
  }
  const end = withoutUse.indexOf(';', start)
  if (end < 0) {
    console.error(`DDL 文件里的 CREATE TABLE 没有结束分号：${TICKET_DDL_FILE}`)
    process.exit(1)
  }
  return withoutUse.slice(start, end + 1)
}

const CREATE_TICKET_SQL = loadCreateTableSql()

// ==================== --print-sql：不连库 ====================
if (PRINT_SQL) {
  console.log('-- 目标库：' + TARGETS.join(', ') + '（每个库各执行下面四段，缺哪补哪）')
  console.log('')
  console.log('-- ① product.return_window_days（仅当列不存在时执行）')
  console.log(ALTER_ADD_RETURN_WINDOW)
  console.log('')
  console.log('-- ② product.return_no_reason（仅当列不存在时执行）')
  console.log(ALTER_ADD_RETURN_NO_REASON)
  console.log('')
  console.log('-- ③ after_sale.cs_ticket_id（仅当列不存在时执行）')
  console.log(ALTER_ADD_CS_TICKET)
  console.log('')
  console.log('-- ④ service_ticket（仅当表不存在时执行；来源：backend/src/main/resources/db/service_ticket.sql）')
  console.log(CREATE_TICKET_SQL)
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

/** 执行一段多语句 SQL：走临时文件 + 输入重定向（Windows 管道会 EBUSY，见 P1-6 脚本注释） */
function execSqlText(sqlText, db) {
  const tmp = path.join(here, '__migrate_return_rule.sql')
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

const columnCount = (db, table, column) =>
  scalar(
    `SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${db}' ` +
    `AND table_name='${table}' AND column_name='${column}';`
  )

/** 补一列：缺则加，加完回读确认 */
function ensureColumn(db, label, table, column, ddl, failures) {
  if (columnCount(db, table, column) === '1') {
    console.log(`OK    ${table}.${column} 已存在，跳过加列`)
    return
  }
  try {
    execSqlText(ddl, db)
    if (columnCount(db, table, column) === '1') {
      console.log(`DONE  已为 ${table} 添加 ${column} 列（${label}）`)
    } else {
      console.log(`FAIL  加列后回读不到 ${table}.${column}，请人工确认`)
      failures.count++
    }
  } catch (e) {
    console.log(`FAIL  为 ${table} 添加 ${column} 失败：` + (e.stderr || e.message))
    failures.count++
  }
}

// ==================== 逐库执行 ====================
const failures = { count: 0 }

for (const db of TARGETS) {
  console.log('')
  console.log(`===== 目标库：${db} =====`)

  const dbExists = scalar(
    `SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${db}';`
  )
  if (dbExists !== '1') {
    console.log(`SKIP  库 ${db} 不存在（先建库或换 --db），未做任何修改`)
    continue
  }

  // product / after_sale 必须在（否则谈不上升级）
  for (const t of ['product', 'after_sale']) {
    const has = scalar(
      `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_name='${t}';`
    )
    if (has !== '1') {
      console.log(`FAIL  库 ${db} 里没有 ${t} 表 —— 请先用 db/init.sql 建库`)
      failures.count++
    }
  }
  if (failures.count > 0) {
    continue
  }

  // ① ② product 两个退货规则列
  ensureColumn(db, '退货退款期限', 'product', 'return_window_days', ALTER_ADD_RETURN_WINDOW, failures)
  ensureColumn(db, '是否支持无理由退货', 'product', 'return_no_reason', ALTER_ADD_RETURN_NO_REASON, failures)

  // ③ after_sale.cs_ticket_id
  ensureColumn(db, '超期放行凭证', 'after_sale', 'cs_ticket_id', ALTER_ADD_CS_TICKET, failures)

  // ④ service_ticket 表
  const hasTicketTable = scalar(
    `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_name='service_ticket';`
  )
  if (hasTicketTable === '1') {
    console.log('OK    service_ticket 表已存在，跳过建表')
  } else {
    try {
      execSqlText(CREATE_TICKET_SQL, db)
      const now = scalar(
        `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_name='service_ticket';`
      )
      const idx = scalar(
        `SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='${db}' AND table_name='service_ticket';`
      )
      if (now === '1') {
        console.log(`DONE  已创建 service_ticket 表（索引条目 ${idx} 项；PK + uk_ticket_no + 4 个普通索引 = 6）`)
      } else {
        console.log('FAIL  建表后回读不到 service_ticket，请人工确认')
        failures.count++
      }
    } catch (e) {
      console.log('FAIL  建表失败：' + (e.stderr || e.message))
      failures.count++
    }
  }
}

console.log('')
console.log(failures.count === 0
  ? '===== 结果：所有目标库均已就绪（含「本来就有、跳过」）====='
  : `===== 结果：有 ${failures.count} 项硬失败，请查看上面的 FAIL 行 =====`)
process.exit(failures.count === 0 ? 0 : 1)
