-- =============================================================================
-- 采购订单表 · 独立建表脚本
-- =============================================================================
-- ⚠️ 本文件与 `init.sql` 中的 `purchase_order` 定义**必须保持完全一致**。
--
-- 【历史坑，2026-09-20 修复】这两份脚本曾经长期互斥：
--   · 本文件 = supplier_name + merchant_id + audit_remark（与 PurchaseOrder.java
--     实体、PurchaseOrderController 完全一致）—— 是对的
--   · init.sql = supplier_id BIGINT NOT NULL，没有 supplier_name ── 是错的
--   谁照着 init.sql 建库，采购模块就直接报
--     Unknown column 'supplier_name' in 'field list'
--   而按本文件建库则一切正常 —— 属于「同一张表两个真相」，
--   排查时极容易被误导。现已统一为下面这一份。
--
-- 权威来源：以 `init.sql` 为准（那是完整建库脚本）。本文件只是
-- 「单独给采购模块补表」时用的便捷脚本，改动请两边同步。
--
-- 遗留字段说明：supplier_id / operator_id / audit_id / audit_time 是旧设计
--   残留，代码已不再读写，保留为可空列待后续迁移；不要直接删列。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `purchase_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购单ID',
    `order_no` VARCHAR(50) NOT NULL COMMENT '采购单号',
    `supplier_name` VARCHAR(200) NOT NULL COMMENT '供应商名称',
    `merchant_id` BIGINT DEFAULT NULL COMMENT '商家ID',
    `supplier_id` BIGINT DEFAULT NULL COMMENT '供应商ID（历史字段，代码已不写入）',
    `total_amount` DECIMAL(12,2) DEFAULT 0 COMMENT '总金额',
    `status` TINYINT DEFAULT 0 COMMENT '状态: 0-待审核, 1-已审核, 2-已完成, 3-审核不通过, 4-已取消',
    `audit_remark` VARCHAR(500) DEFAULT NULL COMMENT '审核备注',
    `remark` TEXT COMMENT '备注',
    `operator_id` BIGINT DEFAULT NULL COMMENT '操作员ID（历史字段，代码已不写入）',
    `audit_id` BIGINT DEFAULT NULL COMMENT '审核员ID（历史字段，代码已不写入）',
    `audit_time` DATETIME DEFAULT NULL COMMENT '审核时间（历史字段，代码已不写入）',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `order_no` (`order_no`),
    INDEX `idx_supplier_id` (`supplier_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单表';
