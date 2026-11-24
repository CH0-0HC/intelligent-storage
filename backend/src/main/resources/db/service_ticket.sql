-- =============================================================================
-- 客服裁定工单表 —— 单独补表 / 升级脚本
-- -----------------------------------------------------------------------------
-- ⚠️ 与 `init.sql` 第 24 节的定义**必须保持完全一致**（改一处务必同步另一处）。
-- 本文件有两种用法：
--   A. 全新库：直接执行本文件建表（或直接跑 init.sql，它已包含本表）；
--   B. 已有库升级：执行本文件末尾的「升级已有库」段落。
--
-- 【为什么需要这张表】详见 init.sql 第 24 节的长注释。一句话概括：
-- 售后时效从「全站写死 15 天」改为「商家按商品设置」后，超期买家必须有
-- 一条申诉通道，客服也必须有地方受理并留痕。本表就是这条通道的载体。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `service_ticket` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '客服工单ID',
    `ticket_no`        VARCHAR(50)  NOT NULL                COMMENT '工单号（唯一）',
    `order_id`         BIGINT       NOT NULL                COMMENT '关联订单ID',
    `order_no`         VARCHAR(50)  DEFAULT NULL            COMMENT '订单号（冗余，便于展示）',
    `order_item_id`    BIGINT       DEFAULT NULL            COMMENT '关联订单明细ID（按商品咨询时非空；整单为 NULL）',
    `product_id`       BIGINT       DEFAULT NULL            COMMENT '商品ID（NULL-整单咨询）',
    `merchant_id`      BIGINT       DEFAULT NULL            COMMENT '商品所属商家ID（申请时快照）',
    `user_id`          BIGINT       NOT NULL                COMMENT '提交工单的买家ID',
    `reason_code`      VARCHAR(30)  NOT NULL                COMMENT '用户诉求原因编码，复用 AfterSaleRule 原因字典',
    `description`      VARCHAR(500) DEFAULT NULL            COMMENT '用户补充说明',
    `images`           TEXT         DEFAULT NULL            COMMENT '凭证图片（JSON 数组）',
    `return_window_days` INT        DEFAULT NULL            COMMENT '该商品退货期限(天)，申请时快照；便于客服看到「超了多久」',
    `days_overdue`     INT          DEFAULT NULL            COMMENT '超期天数（申请时计算并快照）',
    `status`           TINYINT      NOT NULL DEFAULT 0      COMMENT '工单状态: 0-待客服裁定, 1-已裁定, 2-用户已取消',
    `resolution`       TINYINT      DEFAULT NULL            COMMENT '裁定结果: 1-允许继续申请售后, 2-驳回申请',
    `cs_remark`        VARCHAR(500) DEFAULT NULL            COMMENT '客服裁定说明（统一标准依据，双方可见）',
    `operator_id`      BIGINT       DEFAULT NULL            COMMENT '裁定客服ID（sys_user.id）',
    `operate_time`     DATETIME     DEFAULT NULL            COMMENT '裁定时间',
    `consumed`         TINYINT      NOT NULL DEFAULT 0      COMMENT '放行授权是否已使用: 0-未使用, 1-已使用',
    `deleted`          TINYINT      DEFAULT 0               COMMENT '删除标记',
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    INDEX `idx_order_id` (`order_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服裁定工单表（超期售后放行通道）';

-- =============================================================================
-- 升级已有库（幂等：先判存在再 ALTER，重复执行不报错）
-- -----------------------------------------------------------------------------
-- 存量库需要补两处结构：
--   ① product 两个退货规则列（默认 15 / 支持无理由 ⇒ 与改造前行为逐字一致）；
--   ② after_sale.cs_ticket_id（超期放行的可追溯凭证）。
-- 直接用 ALTER 重复执行会报 "Duplicate column name"，故用 information_schema
-- 判存在后动态执行 —— 与 scripts/apply-item-scope-migration.mjs 的手法一致。
-- =============================================================================

-- ① product.return_window_days
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'return_window_days'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `product` ADD COLUMN `return_window_days` INT NOT NULL DEFAULT 15 COMMENT ''退货退款期限(天)，0-不支持退货退款；默认15'' AFTER `is_recommend`',
  'SELECT ''product.return_window_days 已存在，跳过'' AS skip_msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② product.return_no_reason
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'return_no_reason'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `product` ADD COLUMN `return_no_reason` TINYINT NOT NULL DEFAULT 1 COMMENT ''是否支持无理由退货: 0-不支持, 1-支持'' AFTER `return_window_days`',
  'SELECT ''product.return_no_reason 已存在，跳过'' AS skip_msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ after_sale.cs_ticket_id
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'after_sale' AND COLUMN_NAME = 'cs_ticket_id'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `after_sale` ADD COLUMN `cs_ticket_id` BIGINT DEFAULT NULL COMMENT ''客服裁定工单ID（超期放行凭证）；NULL-常规时效内申请'' AFTER `item_scope`',
  'SELECT ''after_sale.cs_ticket_id 已存在，跳过'' AS skip_msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
