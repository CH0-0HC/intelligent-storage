// seed-verify-fixtures.mjs
// 向「独立验证库」补写回归夹具 —— 在 reset-verify-db.mjs 重建库之后、
// 端到端验证脚本启动之前调用（由 regress-all.mjs 编排）。
//
// 【为什么需要这个脚本】
// 两个端到端脚本依赖的并不是 init.sql 里有的种子数据，而是**本机主库里的存量行**：
//   · verify-tenant-fix.mjs   第 40-45 行写死依赖
//       purchase_order id=3（merchant_id IS NULL，管理员建）
//       purchase_order id=4（merchant_id = 2，商家建）
//     脚本自身不建这两行 ⇒ 重建后的验证库里没有它们，
//     相关断言全部拿到 `biz=500 采购订单不存在`（不是产品 bug，是夹具缺失）。
//   · verify-cart-selection.mjs 第 169-175、253 行依赖
//       真实用户 user（id=3）的购物车里有若干行、且初始为全选
//     重建后的验证库里 user 的购物车为空 ⇒ UI 层拿到「购物车为空」全挂。
//   · verify-p0-3-restock.mjs / verify-p0-3-attack-chain.mjs / verify-after-sale*.mjs
//     等「下单→发货→收货→退货回补」用例依赖 **inventory 表有对应商品库存行**：
//     init.sql 的种子只填了 product.stock，**从不写 inventory**（全库 grep 无 INSERT INTO inventory）。
//     于是重建后的验证库 inventory 为空，第一笔「下单/发货」即报
//       {"code":400,"message":"商品「…」商品库存记录不存在，无法下单"}
//     后续「确认收货 500」「订单尚未确认收货」全是这条的连锁误报 ——
//     这不是产品 bug，而是夹具缺失（本项目已登记过的坑：跑任何发货/收货/扣库存
//     用例前必须先播种 inventory）。本脚本第 D 段负责补上，且幂等、不覆盖已有库存。
//
// 【安全边界】
// 只允许写 VERIFY_DB（默认 smart_inventory_it）。VERIFY_DB 指向主库
// smart_inventory 时**直接拒绝执行**，避免把夹具写进老板正在用的真实数据。
//
// 用法：node scripts/seed-verify-fixtures.mjs
// 环境变量：VERIFY_DB / MYSQL_BIN / DB_USERNAME / DB_PASSWORD

import { execFileSync, execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(here, '..')
const TARGET_DB = process.env.VERIFY_DB || 'smart_inventory_it'
const MYSQL_BIN = process.env.MYSQL_BIN || 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const DB_USER = process.env.DB_USERNAME || 'root'
const DB_PASS = process.env.DB_PASSWORD || '123456'

// ---------- 硬保护：绝不碰主库 ----------
if (TARGET_DB === 'smart_inventory') {
  console.error('拒绝执行：VERIFY_DB 指向主库 smart_inventory —— 夹具只允许写入验证库。')
  process.exit(2)
}

// ---------- 第二商家（merchant2）的密码哈希：原样取自 init.sql 的 merchant 行 ----------
// 【为什么不自己造哈希】BCrypt 的算法版本 / cost / 盐都不可控，手写哈希极可能
// 与后端 BCryptPasswordEncoder 校验不通过（本项目 2026-09-20 就因哈希长度错 61 位
// 导致 merchant/user 任何口令都登录不了）。因此直接从权威建库脚本里**原样复制**
// merchant 那条的密码串给 merchant2 —— 二者口令都是 merchant123。
const INIT_SQL = path.join(ROOT, 'backend/src/main/resources/db/init.sql')
let MERCHANT_PASSWORD_HASH = ''
try {
  const initSql = fs.readFileSync(INIT_SQL, 'utf8')
  const m = initSql.match(/\('merchant',\s*'(\$2[aby]\$[^']+)'/)
  if (!m) {
    console.error(`未能从 ${INIT_SQL} 解析出 merchant 的 BCrypt 密码串 —— 无法建立 merchant2 夹具。`)
    process.exit(1)
  }
  MERCHANT_PASSWORD_HASH = m[1]
} catch (e) {
  console.error(`读取 ${INIT_SQL} 失败：${e.message}`)
  process.exit(1)
}

const mysql = (args) =>
  execFileSync(MYSQL_BIN, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

/** 取单值（-N -B 输出制表符分隔，取第一列第一行） */
const scalar = (sql) => {
  const out = mysql([
    '-u' + DB_USER, '-p' + DB_PASS, '--default-character-set=utf8mb4',
    '-D', TARGET_DB, '-N', '-B', '-e', sql
  ]).replace(/\r/g, '').trim()
  return out.split('\n')[0].split('\t')[0]
}

/** 取多行（返回二维数组）。注意 mysql.exe 在 Windows 下输出 CRLF，
 *  必须先把 \r 去掉，否则最后一段值会带 \r，字符串比较全部误判。 */
const rows = (sql) => {
  const out = mysql([
    '-u' + DB_USER, '-p' + DB_PASS, '--default-character-set=utf8mb4',
    '-D', TARGET_DB, '-N', '-B', '-e', sql
  ]).replace(/\r/g, '').trim()
  if (!out) return []
  return out.split('\n').map((l) => l.split('\t'))
}

// ---------- 0. 库与表必须存在 ----------
{
  const dbExists = mysql([
    '-u' + DB_USER, '-p' + DB_PASS, '-N', '-B',
    '-e', `SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${TARGET_DB}';`
  ]).trim()
  if (dbExists !== '1') {
    console.error(`验证库 ${TARGET_DB} 不存在 —— 请先运行 reset-verify-db.mjs。`)
    process.exit(1)
  }
  const need = ['sys_user', 'product', 'cart', 'purchase_order', 'inventory']
  for (const t of need) {
    const n = mysql([
      '-u' + DB_USER, '-p' + DB_PASS, '-N', '-B',
      '-e', `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_name='${t}';`
    ]).trim()
    if (n !== '1') {
      console.error(`验证库 ${TARGET_DB} 缺少表 ${t} —— 请检查 init.sql 导入结果。`)
      process.exit(1)
    }
  }
}

// ---------- 1. 解析 user 的 id（不写死 3，重建库后自增值可能不同）----------
const userId = Number(scalar(`SELECT id FROM sys_user WHERE username='user' LIMIT 1;`))
if (!Number.isFinite(userId) || userId <= 0) {
  console.error("验证库中找不到 username='user' —— init.sql 种子未导入？")
  process.exit(1)
}

// ---------- 2. 校验购物车要引用的商品确实存在 ----------
// 商品取 init.sql 种子里真实存在的 id（1..8），避免插入孤儿行导致列表接口 500。
const CART_FIXTURE = [
  { productId: 1, quantity: 8 },
  { productId: 2, quantity: 1 },
  { productId: 4, quantity: 1 },
  { productId: 6, quantity: 1 },
  { productId: 7, quantity: 1 },
  { productId: 8, quantity: 4 }
]
for (const it of CART_FIXTURE) {
  const n = scalar(`SELECT COUNT(*) FROM product WHERE id=${it.productId} AND deleted=0;`)
  if (n !== '1') {
    console.error(`商品 id=${it.productId} 在验证库中不存在（或已删除）—— 无法建立购物车夹具。`)
    process.exit(1)
  }
}

// ---------- 3. 生成并导入 SQL ----------
const sql = `-- 由 seed-verify-fixtures.mjs 生成：验证库夹具（勿手工编辑）
USE \`${TARGET_DB}\`;

-- A) 采购单夹具：多租户隔离用例（verify-tenant-fix.mjs）依赖 id=3 / id=4
DELETE FROM \`purchase_order\` WHERE \`id\` IN (3, 4);
INSERT INTO \`purchase_order\`
  (\`id\`, \`order_no\`, \`supplier_name\`, \`merchant_id\`, \`total_amount\`, \`status\`, \`remark\`, \`deleted\`)
VALUES
  (3, 'PO202609208332', 'CH集团', NULL, 100000000.00, 1, '验证库夹具：管理员创建（merchant_id 为空）', 0),
  (4, 'PO202609225759', 'CH集团', 2, 100000000.00, 2, '验证库夹具：商家 2 创建', 0);

-- B) 真实用户 user 的购物车夹具：UI 层「初始全选 / 共 6 件」依赖
DELETE FROM \`cart\` WHERE \`user_id\` = ${userId};
INSERT INTO \`cart\` (\`user_id\`, \`product_id\`, \`quantity\`, \`selected\`) VALUES
${CART_FIXTURE.map((it) => `  (${userId}, ${it.productId}, ${it.quantity}, 1)`).join(',\n')};

-- C) 第二商家夹具（P1-6 混合订单归属校验的前提）
--    verify-after-sale-item.mjs 需要「第二个商家 + 其名下商品」才能构造混合订单。
--    本机库与 init.sql 种子都只有 1 个商家，故在此补 merchant2。
--    · 密码哈希**原样取自 init.sql 的 merchant 行**（同一口令 merchant123），不自造哈希；
--    · merchantId 约定 = 该商家用户的 id ⇒ 插入后回读 id 作为其商品的 merchant_id；
--    · 幂等：**按键判存在（INSERT ... WHERE NOT EXISTS）**，重复执行不产生重复账号/商品，
--      也不删重建（避免 id 漂移与 inventory 孤儿行堆积）；只动验证库。
INSERT INTO \`sys_user\` (\`username\`, \`password\`, \`nickname\`, \`role\`, \`status\`)
SELECT 'merchant2', '${MERCHANT_PASSWORD_HASH}', '第二商家（验证夹具）', 'merchant', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM \`sys_user\` WHERE \`username\` = 'merchant2');
SET @merchant2_id = (SELECT \`id\` FROM \`sys_user\` WHERE \`username\` = 'merchant2' LIMIT 1);
INSERT INTO \`product\` (\`name\`, \`category_id\`, \`merchant_id\`, \`price\`, \`cost\`, \`stock\`, \`unit\`, \`status\`, \`is_hot\`, \`is_recommend\`)
SELECT '第二商家验证商品甲', 1, @merchant2_id, 9.90, 5.00, 300, '件', 1, 0, 0
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM \`product\`
                    WHERE \`merchant_id\` = @merchant2_id AND \`name\` = '第二商家验证商品甲' AND \`deleted\` = 0);
INSERT INTO \`product\` (\`name\`, \`category_id\`, \`merchant_id\`, \`price\`, \`cost\`, \`stock\`, \`unit\`, \`status\`, \`is_hot\`, \`is_recommend\`)
SELECT '第二商家验证商品乙', 1, @merchant2_id, 19.90, 10.00, 300, '件', 1, 0, 0
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM \`product\`
                    WHERE \`merchant_id\` = @merchant2_id AND \`name\` = '第二商家验证商品乙' AND \`deleted\` = 0);

-- D) 库存夹具：发货 / 收货 / 退货回补用例的前提
--    init.sql 只写 product.stock，从不写 inventory ⇒ 重建库 inventory 为空，
--    下单即报「商品库存记录不存在，无法下单」。
--    幂等：仅为「未删除且 inventory 尚无对应 product_id」的商品补行
--    （inventory.product_id 有唯一键 uk_product_id；已存在的行一律不动、不覆盖）。
--    quantity 取 product.stock、warning_quantity=10，与项目既有口径一致。
--    注意：本段在 C 段之后执行，故 merchant2 的 2 个商品同样被覆盖。
INSERT INTO \`inventory\` (\`product_id\`, \`quantity\`, \`warning_quantity\`, \`create_time\`, \`update_time\`)
SELECT p.\`id\`, p.\`stock\`, 10, NOW(), NOW()
  FROM \`product\` p
  LEFT JOIN \`inventory\` i ON i.\`product_id\` = p.\`id\`
 WHERE p.\`deleted\` = 0 AND i.\`id\` IS NULL;
`

// 【为什么写临时文件 + 重定向，而不用 execFileSync({ input })】
// 本机 Windows 下走管道会抛 spawnSync mysql.exe EBUSY（reset-verify-db.mjs 已实测）；
// 临时文件放纯 ASCII 路径，规避 cmd 批处理的中文路径编码陷阱。
const tmp = path.join(here, '__seed_it.sql')
fs.writeFileSync(tmp, sql, 'utf8')
try {
  execSync(
    `"${MYSQL_BIN}" -u${DB_USER} -p${DB_PASS} --default-character-set=utf8mb4 < "${tmp}"`,
    { stdio: ['ignore', 'pipe', 'pipe'] }
  )
} finally {
  fs.unlinkSync(tmp)
}

// ---------- 4. 回读校验（写进去的行数必须与预期一致）----------
const poRows = rows(
  `SELECT id, order_no, IFNULL(merchant_id,'NULL'), total_amount, status, deleted
     FROM purchase_order WHERE id IN (3,4) ORDER BY id;`
)
const cartRows = rows(
  `SELECT id, user_id, product_id, quantity, selected, deleted
     FROM cart WHERE user_id=${userId} ORDER BY product_id;`
)
const poTotal = scalar(`SELECT COUNT(*) FROM purchase_order;`)
const cartTotal = scalar(`SELECT COUNT(*) FROM cart;`)

// 库存夹具回读：每个未删除商品都应有 inventory 行；且 quantity 必须等于 product.stock
const invMissing = rows(
  `SELECT p.id, p.name
     FROM product p LEFT JOIN inventory i ON i.product_id = p.id
    WHERE p.deleted = 0 AND i.id IS NULL ORDER BY p.id;`
)
const invMismatch = rows(
  `SELECT i.product_id, i.quantity, p.stock
     FROM inventory i JOIN product p ON p.id = i.product_id
    WHERE p.deleted = 0 AND i.quantity <> p.stock ORDER BY i.product_id;`
)
const invTotal = scalar(`SELECT COUNT(*) FROM inventory;`)
const prodTotal = scalar(`SELECT COUNT(*) FROM product WHERE deleted = 0;`)

console.log(`验证库 ${TARGET_DB} 夹具写入完成：`)
console.log(`  user 账号 id = ${userId}`)
console.log(`  purchase_order 共 ${poTotal} 行，其中夹具：`)
for (const r of poRows) {
  console.log(`    id=${r[0]} order_no=${r[1]} merchant_id=${r[2]} amount=${r[3]} status=${r[4]} deleted=${r[5]}`)
}
console.log(`  cart 共 ${cartTotal} 行，其中 user(id=${userId}) 的 ${cartRows.length} 行：`)
for (const r of cartRows) {
  console.log(`    id=${r[0]} user_id=${r[1]} product_id=${r[2]} quantity=${r[3]} selected=${r[4]} deleted=${r[5]}`)
}
console.log(`  inventory 共 ${invTotal} 行，覆盖未删除商品 ${prodTotal - invMissing.length}/${prodTotal}：`)
for (const r of rows(`SELECT product_id, quantity, warning_quantity FROM inventory ORDER BY product_id;`)) {
  console.log(`    product_id=${r[0]} quantity=${r[1]} warning_quantity=${r[2]}`)
}

// 第二商家（merchant2）夹具回读：账号字段齐备 + 其 2 个商品归属正确 + inventory 覆盖
const m2Rows = rows(
  `SELECT id, username, password, nickname, role, status FROM sys_user WHERE username='merchant2' ORDER BY id;`
)
const m2Id = m2Rows.length === 1 ? m2Rows[0][0] : '0'
const m2Products = rows(
  `SELECT p.id, p.name, p.merchant_id, p.stock, IFNULL(i.quantity,'NULL'), p.deleted
     FROM product p LEFT JOIN inventory i ON i.product_id = p.id
    WHERE p.merchant_id = ${m2Id} ORDER BY p.id;`
)
console.log(`  merchant2（第二商家）账号：`)
if (m2Rows.length === 1) {
  const r = m2Rows[0]
  console.log(`    id=${r[0]} username=${r[1]} role=${r[4]} status=${r[5]} nickname=${r[3]} ` +
    `password==merchant哈希? ${r[2] === MERCHANT_PASSWORD_HASH}`)
} else {
  console.log(`    (未找到，实际 ${m2Rows.length} 行)`)
}
console.log(`  merchant2(id=${m2Id}) 名下商品 ${m2Products.length} 个：`)
for (const r of m2Products) {
  console.log(`    product_id=${r[0]} name=${r[1]} merchant_id=${r[2]} stock=${r[3]} inventory=${r[4]} deleted=${r[5]}`)
}

const problems = []
if (poRows.length !== 2) problems.push(`purchase_order 夹具应为 2 行，实际 ${poRows.length}`)
if (cartRows.length !== CART_FIXTURE.length) {
  problems.push(`cart 夹具应为 ${CART_FIXTURE.length} 行，实际 ${cartRows.length}`)
}
if (cartRows.some((r) => r[4] !== '1' || r[5] !== '0')) {
  problems.push('cart 夹具存在 selected!=1 或 deleted!=0 的行')
}
if (poRows.length === 2) {
  if (poRows[0][2] !== 'NULL') problems.push(`purchase_order id=3 的 merchant_id 应为 NULL，实际 ${poRows[0][2]}`)
  if (poRows[1][2] !== '2') problems.push(`purchase_order id=4 的 merchant_id 应为 2，实际 ${poRows[1][2]}`)
}
// 断言：第二商家（merchant2）账号必须齐备，且密码哈希与 merchant 完全一致（可登录 merchant123）
if (m2Rows.length !== 1) {
  problems.push(`merchant2 账号应恰好 1 行，实际 ${m2Rows.length}`)
} else {
  const r = m2Rows[0]
  if (r[4] !== 'merchant') problems.push(`merchant2 的 role 应为 merchant，实际 ${r[4]}`)
  if (r[5] !== '1') problems.push(`merchant2 的 status 应为 1（可登录），实际 ${r[5]}`)
  if (!r[2] || r[2] !== MERCHANT_PASSWORD_HASH) {
    problems.push('merchant2 的密码哈希与 merchant 不一致（将无法用 merchant123 登录）')
  }
}
// 断言：merchant2 名下恰好 2 个未删除商品，且归属/库存正确
if (m2Products.length !== 2) {
  problems.push(`merchant2 名下商品应为 2 个，实际 ${m2Products.length}`)
} else {
  for (const r of m2Products) {
    if (r[2] !== m2Id) problems.push(`商品 id=${r[0]} 的 merchant_id 应为 ${m2Id}，实际 ${r[2]}`)
    if (r[5] !== '0') problems.push(`商品 id=${r[0]} 应为未删除(deleted=0)，实际 ${r[5]}`)
    if (r[4] === 'NULL') problems.push(`商品 id=${r[0]} 缺 inventory 行`)
  }
}
// 断言：inventory 必须覆盖全部未删除商品（发货/收货/退货回补用例的前提）
if (invMissing.length > 0) {
  problems.push(
    `inventory 未覆盖全部未删除商品：缺 ${invMissing.length} 个 → ` +
    invMissing.map((r) => `id=${r[0]}(${r[1]})`).join(', ')
  )
}
if (invMismatch.length > 0) {
  problems.push(
    `inventory.quantity 与 product.stock 不一致：` +
    invMismatch.map((r) => `product_id=${r[0]} inv=${r[1]} stock=${r[2]}`).join(', ')
  )
}
if (problems.length) {
  console.error('\n夹具校验未通过：')
  problems.forEach((p) => console.error('  - ' + p))
  process.exit(1)
}
console.log('夹具校验通过 ✅')
