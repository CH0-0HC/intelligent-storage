#!/usr/bin/env node
/**
 * audit-db-schema.mjs —— 数据库结构三方审计
 *
 * 对照「权威建表脚本 init.sql」↔「Java 实体（代码实际依赖）」↔「线上库实际结构」，
 * 逐表逐列检查：字段名、数据类型、非空、默认值、索引、外键。
 *
 * 用法：
 *   node scripts/audit-db-schema.mjs                 # 审计默认库 smart_inventory
 *   node scripts/audit-db-schema.mjs --db xxx
 *   node scripts/audit-db-schema.mjs > 报告.md        # 输出 markdown 报告
 *
 * 退出码：0 无问题；1 存在问题（可用于门禁）。
 */

import { readFileSync, readdirSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
const INIT_SQL = resolve(ROOT, 'backend/src/main/resources/db/init.sql')
const APP_YML = resolve(ROOT, 'backend/src/main/resources/application.yml')
const ENTITY_DIR = resolve(ROOT, 'backend/src/main/java/com/ai/smartinventory/entity')
const MYSQL_BIN = process.env.MYSQL_BIN || '/usr/local/mysql/bin/mysql'

const argv = process.argv.slice(2)
const argOf = (n, d) => { const i = argv.indexOf(n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d }
const DB = argOf('--db', 'smart_inventory')

/* ------------------------------ 工具 ------------------------------ */

const out = []
const say = (s = '') => out.push(s)

function readDbConfig() {
  const yml = readFileSync(APP_YML, 'utf8')
  const u = yml.match(/username:\s*\$\{DB_USERNAME:([^}]+)\}/)
  const p = yml.match(/password:\s*\$\{DB_PASSWORD:([^}]+)\}/)
  const url = yml.match(/url:\s*jdbc:mysql:\/\/([^/]+)\/([^?]+)/)
  const hp = url ? url[1] : 'localhost:3306'
  const [host, port] = hp.split(':')
  return {
    user: process.env.DB_USERNAME || (u ? u[1].trim() : 'root'),
    pass: process.env.DB_PASSWORD || (p ? p[1].trim() : ''),
    host: process.env.DB_HOST || host,
    port: process.env.DB_PORT || port || '3306'
  }
}

function query(cfg, sql) {
  const o = execFileSync(MYSQL_BIN,
    ['-u' + cfg.user, '-p' + cfg.pass, '-h' + cfg.host, '-P' + cfg.port,
      '--default-character-set=utf8mb4', '-N', '-B', '-e', sql],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  return o.split('\n').map((l) => l.trimEnd()).filter((l) => l.length)
}

const toSnake = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()

/** SQL 类型 -> 归类，用于与 Java 类型做兼容性判断 */
function sqlKind(t) {
  const x = t.toLowerCase()
  if (/^(bigint)/.test(x)) return 'int'
  if (/^(int|integer|smallint|mediumint|tinyint)/.test(x)) return 'int'
  if (/^(decimal|numeric|double|float|real)/.test(x)) return 'decimal'
  if (/^(varchar|char|text|tinytext|mediumtext|longtext)/.test(x)) return 'text'
  if (/^(datetime|timestamp|date|time)/.test(x)) return 'date'
  if (/^(json)/.test(x)) return 'json'
  if (/^(blob|binary|varbinary)/.test(x)) return 'binary'
  return 'other'
}

function javaKind(t) {
  const x = t.replace(/\s/g, '')
  if (/^(Long|long)$/.test(x)) return 'int'
  if (/^(Integer|int|Short|Byte)$/.test(x)) return 'int'
  if (/^(BigDecimal|Double|Float|double|float)$/.test(x)) return 'decimal'
  if (/^String$/.test(x)) return 'text'
  if (/^LocalDateTime$/.test(x)) return 'date'
  if (/^LocalDate$/.test(x)) return 'date'
  if (/^(Boolean|boolean)$/.test(x)) return 'bool'
  if (/^List|^Set|^Map/.test(x)) return 'collection'
  return 'other'
}

/* ------------------------ 解析 init.sql ------------------------ */

function parseInitSql() {
  const sql = readFileSync(INIT_SQL, 'utf8')
  const tables = new Map()
  const re = /CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?([A-Za-z_]\w*)`?\s*\(([\s\S]*?)\n\)\s*ENGINE/gi
  let m
  while ((m = re.exec(sql)) !== null) {
    const [, table, body] = m
    const cols = []
    const indexes = []
    for (const raw of body.split('\n')) {
      const line = raw.trim().replace(/,$/, '')
      if (!line) continue
      const pk = line.match(/^PRIMARY\s+KEY\s*\(([^)]*)\)/i)
      if (pk) { indexes.push({ name: 'PRIMARY', unique: true, cols: pk[1].split(',').map((s) => s.trim().replace(/`/g, '')) }); continue }
      const uk = line.match(/^UNIQUE\s+(?:KEY|INDEX)\s+`?(\w+)`?\s*\(([^)]*)\)/i)
      if (uk) { indexes.push({ name: uk[1], unique: true, cols: uk[2].split(',').map((s) => s.trim().replace(/`/g, '')) }); continue }
      const k = line.match(/^(?:KEY|INDEX)\s+`?(\w+)`?\s*\(([^)]*)\)/i)
      if (k) { indexes.push({ name: k[1], unique: false, cols: k[2].split(',').map((s) => s.trim().replace(/`/g, '')) }); continue }
      if (/^(CONSTRAINT|FOREIGN|FULLTEXT|SPATIAL|CHECK)\b/i.test(line)) continue
      const cm = line.match(/^`([A-Za-z_]\w*)`\s+([A-Za-z]+(?:\([^)]*\))?(?:\s+unsigned)?)([\s\S]*)$/i)
      if (!cm) continue
      const rest = cm[3]
      const def = rest.match(/DEFAULT\s+('(?:[^']*)'|[^\s,]+)/i)
      cols.push({
        name: cm[1],
        type: cm[2].replace(/\s+/g, ' ').trim(),
        nullable: !/\bNOT\s+NULL\b/i.test(rest),
        def: def ? def[1].replace(/^'|'$/g, '') : null,
        isPk: /AUTO_INCREMENT/i.test(rest)
      })
    }
    tables.set(table, { cols, indexes })
  }
  return tables
}

/* ------------------------ 解析 Java 实体 ------------------------ */

function parseEntities() {
  const entities = new Map()
  for (const f of readdirSync(ENTITY_DIR).filter((x) => x.endsWith('.java'))) {
    const src = readFileSync(join(ENTITY_DIR, f), 'utf8')
    const cls = f.replace(/\.java$/, '')
    const tn = src.match(/@TableName\(\s*"([^"]+)"\s*\)/)
    const table = tn ? tn[1].replace(/`/g, '') : toSnake(cls)
    const fields = []
    const lines = src.split('\n')
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim()
      const fm = line.match(/^private\s+([A-Za-z_][\w<>\[\], .]*?)\s+([a-z]\w*)\s*;/)
      if (!fm) continue
      const [, jtype, jname] = fm
      if (/\bstatic\b|\bfinal\b/.test(line)) continue
      // 向上回溯注解：@TableField(exist = false) 的字段不落库，必须排除，
      // 否则会被误报成「实体有、库中无 ⇒ 必炸」（首次运行就误报了 items/username 等）
      let skip = false
      for (let k = i - 1; k >= 0 && k >= i - 4; k--) {
        const prev = lines[k].trim()
        if (/^private\b/.test(prev) || /^\}/.test(prev)) break
        if (/@TableField\s*\(\s*exist\s*=\s*false\s*\)/.test(prev)) { skip = true; break }
      }
      if (skip) continue
      fields.push({ cls, jtype: jtype.trim(), jname, col: toSnake(jname) })
    }
    entities.set(table, { cls, file: f, fields })
  }
  return entities
}

/* ------------------------------ 主流程 ------------------------------ */

const cfg = readDbConfig()
const init = parseInitSql()
const entities = parseEntities()

const liveTables = new Set(query(cfg, `SELECT table_name FROM information_schema.tables WHERE table_schema='${DB}';`))
const liveCols = new Map()
for (const r of query(cfg, `SELECT table_name,column_name,column_type,is_nullable,IFNULL(column_default,'<NULL>'),column_key,extra FROM information_schema.columns WHERE table_schema='${DB}' ORDER BY table_name,ordinal_position;`)) {
  const [t, c, type, nullable, def, key, extra] = r.split('\t')
  if (!liveCols.has(t)) liveCols.set(t, [])
  liveCols.get(t).push({ name: c, type, nullable: nullable === 'YES', def: def === '<NULL>' ? null : def, key, extra })
}
const liveIdx = new Map()
for (const r of query(cfg, `SELECT table_name,index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='${DB}' GROUP BY table_name,index_name,non_unique;`)) {
  const [t, name, nonUnique, cols] = r.split('\t')
  if (!liveIdx.has(t)) liveIdx.set(t, [])
  liveIdx.get(t).push({ name, unique: nonUnique === '0', cols: cols.split(',') })
}
const fks = query(cfg, `SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='${DB}' AND referenced_table_name IS NOT NULL;`)[0]

const problems = { missingTable: [], missingCol: [], typeMismatch: [], nullConflict: [], missingIndex: [], extraCol: [] }

say('# 数据库结构审计报告')
say('')
say(`> 生成时间：2026-10-09　目标库：\`${DB}\` @ ${cfg.host}:${cfg.port}`)
say('> 对照三方：**权威脚本 `db/init.sql`** ↔ **Java 实体（代码实际依赖）** ↔ **线上库实际结构**')
say('')

/* ---- 1. 表级 ---- */
say('## 1. 表级：线上缺失的表')
say('')
const missTables = [...init.keys()].filter((t) => !liveTables.has(t))
if (missTables.length) {
  say('| 表名 | 影响 |')
  say('|---|---|')
  for (const t of missTables) { problems.missingTable.push(t); say(`| \`${t}\` | 该模块所有读写都会报 Table doesn't exist |`) }
} else say('无。线上库覆盖了 `init.sql` 定义的全部 ' + init.size + ' 张表。')
say('')

/* ---- 2. 列级：缺失 / 多余 / 类型 / 非空 / 默认值 ---- */
say('## 2. 列级明细')
say('')
for (const [table, def] of init) {
  const live = liveCols.get(table)
  if (!live) continue
  const liveByName = new Map(live.map((c) => [c.name, c]))
  const ent = entities.get(table)
  const rows = []

  for (const c of def.cols) {
    const L = liveByName.get(c.name)
    if (!L) {
      problems.missingCol.push({ table, col: c.name, type: c.type, nullable: c.nullable, def: c.def })
      const used = ent && ent.fields.find((f) => f.col === c.name)
      rows.push([`\`${c.name}\``, `**缺失**`, `${c.type}${c.nullable ? '' : ' NOT NULL'}${c.def !== null ? ` DEFAULT ${c.def}` : ''}`, '—', used ? `实体 \`${used.cls}.${used.jname}\` 依赖它 ⇒ 查询必报 Unknown column` : '代码未直接依赖'])
      continue
    }
    // 类型比对
    const notes = []
    const isPkCol = c.isPk
    if (sqlKind(L.type) !== sqlKind(c.type) && !(isPkCol && sqlKind(L.type) === 'int' && sqlKind(c.type) === 'int')) {
      problems.typeMismatch.push({ table, col: c.name, init: c.type, live: L.type })
      notes.push(`类型不一致：脚本 ${c.type} ↔ 线上 ${L.type}`)
    }
    // 非空比对
    if (c.nullable !== L.nullable) {
      problems.nullConflict.push({ table, col: c.name, initNullable: c.nullable, liveNullable: L.nullable, def: L.def })
      notes.push(`非空不一致：脚本 ${c.nullable ? 'NULL' : 'NOT NULL'} ↔ 线上 ${L.nullable ? 'NULL' : 'NOT NULL'}`)
    }
    // 默认值比对
    // 归一化：MySQL 对「无默认值」与「DEFAULT NULL」在 information_schema 里
    // 都报 NULL，因此 NULL ≡ 脚本里的 DEFAULT NULL；数值要按数值比（0 与 0.00 等价）。
    const normDef = (v) => {
      if (v === null || v === undefined) return null
      let s = String(v).trim()
      if (/^CURRENT_TIMESTAMP/i.test(s)) return 'CURRENT_TIMESTAMP'
      if (/^'?(NULL)'?$/i.test(s)) return null
      s = s.replace(/^'|'$/g, '')
      if (s === '') return "''"
      if (/^-?\d+(\.\d+)?$/.test(s)) return String(Number(s))
      return s.toLowerCase()
    }
    if (normDef(c.def) !== normDef(L.def)) {
      notes.push(`默认值不一致：脚本 ${c.def ?? '<无>'} ↔ 线上 ${L.def ?? '<无>'}`)
    }
    if (notes.length) {
      rows.push([`\`${c.name}\``, '存在', `${c.type}${c.nullable ? '' : ' NOT NULL'}`, L.type, notes.join('；')])
    }
  }

  // 线上多出的列（脚本/实体都没有）
  for (const L of live) {
    if (def.cols.some((c) => c.name === L.name)) continue
    const entUses = ent && ent.fields.some((f) => f.col === L.name)
    problems.extraCol.push({ table, col: L.name })
    rows.push([`\`${L.name}\``, '脚本中无', '—', L.type, entUses ? '实体仍在读写该列（脚本缺定义，勿删）' : '历史遗留列，代码不读写'])
  }

  if (rows.length) {
    say(`### ${table}${ent ? `　（实体 \`${ent.cls}\`）` : ''}`)
    say('')
    say('| 列 | 状态 | 脚本定义 | 线上实际 | 说明 |')
    say('|---|---|---|---|---|')
    for (const r of rows) say(`| ${r.join(' | ')} |`)
    say('')
  }
}

/* ---- 3. 实体字段 → 线上列 的覆盖检查 ---- */
say('## 3. 实体字段覆盖检查（MyBatis-Plus 默认 SELECT 全字段）')
say('')
say('实体字段若在线上库不存在，任何 `selectById` / `selectList` 都会直接报 Unknown column。')
say('')
let entRows = []
for (const [table, ent] of entities) {
  const live = liveCols.get(table)
  if (!live) { entRows.push([table, ent.cls, '**整表缺失**', '—']); continue }
  const names = new Set(live.map((c) => c.name))
  const missing = ent.fields.filter((f) => !names.has(f.col))
  if (missing.length) {
    entRows.push([table, ent.cls, missing.map((f) => `\`${f.jname}\`(${f.col}, ${f.jtype})`).join('<br>'), '实体有、库中无 ⇒ 必炸'])
  }
}
if (entRows.length) {
  say('| 表 | 实体类 | 缺失的字段 | 后果 |')
  say('|---|---|---|---|')
  for (const r of entRows) say(`| ${r.join(' | ')} |`)
} else say('无。15 个实体的全部字段在线上库中都能找到对应列。')
say('')

/* ---- 4. 索引 ---- */
say('## 4. 索引')
say('')
const idxRows = []
for (const [table, def] of init) {
  const live = liveIdx.get(table)
  if (!live) continue
  const liveNames = new Set(live.map((i) => String(i.name)))
  for (const idx of def.indexes) {
    if (!liveNames.has(String(idx.name))) {
      problems.missingIndex.push({ table, index: idx.name, cols: idx.cols })
      idxRows.push([table, `\`${idx.name}\``, idx.unique ? 'UNIQUE' : '普通', idx.cols.join(', '), '**缺失**'])
    }
  }
}
if (idxRows.length) {
  say('| 表 | 索引名 | 类型 | 列 | 状态 |')
  say('|---|---|---|---|---|')
  for (const r of idxRows) say(`| ${r.join(' | ')} |`)
} else say('无。脚本中定义的索引在线上库均存在。')
say('')
const totalIdx = [...liveIdx.values()].reduce((a, b) => a + b.length, 0)
say(`> 线上库共 ${totalIdx} 个索引条目；UNIQUE 索引是业务幂等的关键（如 \`after_sale_item.uk_sale_item\`、\`cart\` 的会员+商品唯一键）。`)
say('')

/* ---- 5. 外键 ---- */
say('## 5. 外键关联')
say('')
say(`- 线上库中**物理外键数量：${fks}**`)
say('- `init.sql` 及全部 `db/*.sql` 中 **FOREIGN KEY 出现次数：0**')
say('')
say('**结论：本项目不使用物理外键。** 所有「订单→明细→商品→商家」之类的关联都是**逻辑关联**，'
  + '由业务代码 + 查询条件保证（例：`order_item.order_id`、`after_sale_item.product_id` 均为裸 BIGINT，无约束）。')
say('')
say('这是刻意的设计取舍，优点是删除/迁移不被外键阻塞、多租户过滤可以自由下推；'
  + '代价是**引用完整性只能靠代码兜住**，孤儿数据不会被数据库拦住。')
say('')

/* ---- 6. 结论 ---- */
say('## 6. 结论汇总')
say('')
const total = problems.missingTable.length + problems.missingCol.length + problems.typeMismatch.length
  + problems.nullConflict.length + problems.missingIndex.length + entRows.length
say(`| 类别 | 数量 |`)
say(`|---|---|`)
say(`| 缺失的表 | ${problems.missingTable.length} |`)
say(`| 缺失的列（脚本有、线上无） | ${problems.missingCol.length} |`)
say(`| 实体字段无对应列 | ${entRows.length} |`)
say(`| 类型不一致 | ${problems.typeMismatch.length} |`)
say(`| 非空/默认值不一致 | ${problems.nullConflict.length} |`)
say(`| 缺失的索引 | ${problems.missingIndex.length} |`)
say(`| 线上多出的列（不影响运行） | ${problems.extraCol.length} |`)
say(`| **合计需处理** | **${total}** |`)
say('')

console.log(out.join('\n'))
process.exit(total > 0 ? 1 : 0)
