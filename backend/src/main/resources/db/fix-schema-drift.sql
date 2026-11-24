-- =============================================================================
-- 结构漂移修复：把线上库补齐到 db/init.sql 的权威定义
-- -----------------------------------------------------------------------------
-- 【为什么需要这个脚本】
-- 2026-10-09 审计发现线上库虽是「24 张表齐全」，但 3 张表的结构停留在**更早版本的
-- init.sql**：缺列 / 缺索引 / 非空约束过严。编译期与单测都发现不了（H2 内存库不跑
-- 生产 DDL），只有真连库点到对应页面才 500。已确认的现场：
--   · 商家点「采购管理」→ Unknown column 'merchant_id' in 'where clause'
--   · 购物车加购/列表    → Unknown column 'selected'
--   · 订单列表/发货      → Unknown column 'logistics_company'
--
-- 【幂等】每处改动都先用 information_schema 判存在再动态执行，
-- 重复运行只会打印「已存在，跳过」，不会报 1060 Duplicate column / 1061 Duplicate key。
-- 手法与 db/service_ticket.sql、scripts/apply-item-scope-migration.mjs 一致。
--
-- 【用法】
--   /usr/local/mysql/bin/mysql -uroot -p --default-character-set=utf8mb4 \
--     smart_inventory < backend/src/main/resources/db/fix-schema-drift.sql
--   ⚠️ 必须显式带库名：本文件用 DATABASE() 做判存在，没有写死 USE。
--   ⚠️ 改完必须重启后端实例（本项目的已知纪律：改 yml/库结构不重启会继续用旧连接/旧元数据）。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1) purchase_order —— 权威定义见 db/init.sql 与 db/purchase_order.sql
--    缺 supplier_name / merchant_id / audit_remark，且 supplier_id 非空过严
-- ---------------------------------------------------------------------------

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_order' AND COLUMN_NAME = 'supplier_name');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `purchase_order` ADD COLUMN `supplier_name` VARCHAR(200) NOT NULL COMMENT ''供应商名称'' AFTER `order_no`',
  'SELECT ''purchase_order.supplier_name 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_order' AND COLUMN_NAME = 'merchant_id');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `purchase_order` ADD COLUMN `merchant_id` BIGINT DEFAULT NULL COMMENT ''商家ID'' AFTER `supplier_name`',
  'SELECT ''purchase_order.merchant_id 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_order' AND COLUMN_NAME = 'audit_remark');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `purchase_order` ADD COLUMN `audit_remark` VARCHAR(500) DEFAULT NULL COMMENT ''审核备注'' AFTER `status`',
  'SELECT ''purchase_order.audit_remark 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- supplier_id / operator_id / audit_id / audit_time 是旧设计残留，代码已不读写，
-- 权威定义是可空列。线上仍是 NOT NULL ⇒ 新增采购单时 MP 不写该列，插入会直接失败。
SET @notnull := (SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_order' AND COLUMN_NAME = 'supplier_id');
SET @ddl := IF(@notnull = 'NO',
  'ALTER TABLE `purchase_order` MODIFY COLUMN `supplier_id` BIGINT DEFAULT NULL COMMENT ''供应商ID（历史字段，代码已不写入）''',
  'SELECT ''purchase_order.supplier_id 已可空，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase_order' AND INDEX_NAME = 'idx_merchant_id');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `purchase_order` ADD INDEX `idx_merchant_id` (`merchant_id`)',
  'SELECT ''purchase_order.idx_merchant_id 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 2) cart —— 缺 selected（购物车勾选参与结算，CartServiceImpl 读写）
-- ---------------------------------------------------------------------------

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cart' AND COLUMN_NAME = 'selected');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `cart` ADD COLUMN `selected` TINYINT NOT NULL DEFAULT 1 COMMENT ''是否选中: 1-选中, 0-未选中'' AFTER `quantity`',
  'SELECT ''cart.selected 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 3) order —— 缺物流三列（发货链路 + Order 实体 SELECT 全字段）
-- ---------------------------------------------------------------------------

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'logistics_company');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `order` ADD COLUMN `logistics_company` VARCHAR(100) DEFAULT NULL COMMENT ''物流公司'' AFTER `complete_time`',
  'SELECT ''order.logistics_company 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'tracking_no');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `order` ADD COLUMN `tracking_no` VARCHAR(100) DEFAULT NULL COMMENT ''物流单号'' AFTER `logistics_company`',
  'SELECT ''order.tracking_no 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'ship_time');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `order` ADD COLUMN `ship_time` DATETIME DEFAULT NULL COMMENT ''发货时间'' AFTER `tracking_no`',
  'SELECT ''order.ship_time 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 4) order —— 缺 cancel_reason（15 分钟未付款自动取消需要区分「超时自动取消」
--    与「用户/商家主动取消」，前端据此给出不同提示）
-- ---------------------------------------------------------------------------

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'cancel_reason');
SET @ddl := IF(@exists = 0,
  'ALTER TABLE `order` ADD COLUMN `cancel_reason` VARCHAR(30) DEFAULT NULL COMMENT ''取消原因: AUTO_TIMEOUT-超时未付款自动取消, USER-用户取消, MERCHANT-商家关闭'' AFTER `cancel_time`',
  'SELECT ''order.cancel_reason 已存在，跳过'' AS skip_msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 5) 校验输出
-- ---------------------------------------------------------------------------
SELECT '修复后校验' AS stage;
SELECT table_name, column_name
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND ((table_name = 'purchase_order' AND column_name IN ('supplier_name','merchant_id','audit_remark'))
     OR (table_name = 'cart' AND column_name = 'selected')
     OR (table_name = 'order' AND column_name IN ('logistics_company','tracking_no','ship_time','cancel_reason')))
 ORDER BY table_name, ordinal_position;
