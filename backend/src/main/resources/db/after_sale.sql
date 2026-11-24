-- =============================================================================
-- 售后申请单表（单独补表脚本，与 db/init.sql 第 22 节保持一致）
-- -----------------------------------------------------------------------------
-- 【为什么建这张表】
-- 缺陷背景：系统只有「订单」一个域，没有任何售后域的表 / 实体 / 接口。
-- 用户确认收货（order.order_status = 3）后，前端订单列表只渲染「再次购买」，
-- 页面上不存在申请售后入口 —— 因为该功能从未实现，而不是入口被条件隐藏。
--
-- 【设计口径：对齐主流电商（淘宝 / 京东）】
--   · 入口有效期：交易成功（确认收货）后 15 天内可申请售后
--   · 7 天无理由：仅退款 / 退货退款 中的「无理由」原因仅限确认收货后 7 天内
--   · 同订单并发：同一订单同一时刻只允许存在一个进行中（待处理 / 已同意）的售后单
--   · 被拒绝 / 已撤销后：在剩余时效内允许重新申请，故 order_id 上**不加唯一索引**
--     （唯一索引 + 逻辑删除 = 本项目已踩过的坑：删除后重新插入同一业务键会撞唯一键）
--
-- 【无 merchant_id 列的原因】
-- 与 order 表同样的问题：一个订单可能包含多个商家的商品，售后单无法挂单一 merchant_id。
-- 商家侧过滤统一走「order → order_item → product.merchant_id 反查」，
-- 与本项目既有范式（OrderMapper.selectOrderIdsByMerchantId）保持一条路径。
-- =============================================================================

USE smart_inventory;

CREATE TABLE IF NOT EXISTS `after_sale` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '售后单ID',
    `after_sale_no` VARCHAR(50)  NOT NULL                COMMENT '售后单号（唯一）',
    `order_id`      BIGINT       NOT NULL                COMMENT '关联订单ID',
    `order_no`      VARCHAR(50)  DEFAULT NULL            COMMENT '订单号（冗余，便于展示）',
    `user_id`       BIGINT       NOT NULL                COMMENT '申请用户ID（买家）',
    `type`          TINYINT      NOT NULL DEFAULT 1      COMMENT '售后类型: 1-仅退款, 2-退货退款, 3-换货',
    `reason_code`   VARCHAR(30)  NOT NULL                COMMENT '原因编码: no_reason-7天无理由, quality-质量问题, mismatch-与描述不符, damaged-破损少件, late-未按约定时间发货, other-其他',
    `reason`        VARCHAR(200) DEFAULT NULL            COMMENT '原因文案（服务端按编码生成的展示文案）',
    `description`   VARCHAR(500) DEFAULT NULL            COMMENT '买家补充说明',
    `images`        TEXT         DEFAULT NULL            COMMENT '凭证图片（JSON 数组）',
    `refund_amount` DECIMAL(12,2) NOT NULL DEFAULT 0     COMMENT '申请退款金额；换货为 0',
    `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '售后状态: 0-待处理, 1-商家已同意, 2-已完成, 3-商家已拒绝, 4-买家已撤销',
    `apply_time`    DATETIME     NOT NULL                COMMENT '申请时间',
    `audit_time`    DATETIME     DEFAULT NULL            COMMENT '商家处理时间',
    `audit_remark`  VARCHAR(500) DEFAULT NULL            COMMENT '商家处理备注 / 拒绝原因',
    `finish_time`   DATETIME     DEFAULT NULL            COMMENT '售后完成时间',
    `operator_id`   BIGINT       DEFAULT NULL            COMMENT '处理人ID',
    `deleted`       TINYINT      DEFAULT 0               COMMENT '删除标记',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_after_sale_no` (`after_sale_no`),
    INDEX `idx_order_id` (`order_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后申请单表';

-- =============================================================================
-- 升级已有库：after_sale 增加「售后范围」列（P1-6 按商品粒度改造 B1）
-- -----------------------------------------------------------------------------
-- 【新库由 init.sql 建表时已包含，此处仅用于升级】
-- backend/src/main/resources/db/init.sql 第 22 节的 CREATE TABLE after_sale
-- 已经带上了 `item_scope` 列；本段是给「已经建好 after_sale 表」的库补列用的。
--
-- ⚠️ 幂等：重复执行会报 1060 Duplicate column name。
--    生产库执行前请先按 information_schema.COLUMNS 判存在，再决定是否执行本语句，
--    例如：
--      SELECT COUNT(*) FROM information_schema.COLUMNS
--       WHERE TABLE_SCHEMA = 'smart_inventory' AND TABLE_NAME = 'after_sale'
--         AND COLUMN_NAME = 'item_scope';
--      -- 结果为 0 才执行下面的 ALTER。
--
-- ✅【推荐做法，2026-09-28】不要手工跑本段，改用幂等脚本（已内含同样的判存在逻辑，
--    并会一并补齐 after_sale_item 表；可重复执行，第二次全部打印「已存在，跳过」）：
--      node scripts/apply-item-scope-migration.mjs                    # 默认升级 smart_inventory
--      node scripts/apply-item-scope-migration.mjs --db smart_inventory_it
--      node scripts/apply-item-scope-migration.mjs --print-sql        # 只看将执行的 SQL
-- =============================================================================
ALTER TABLE `after_sale`
    ADD COLUMN `item_scope` TINYINT NOT NULL DEFAULT 0
        COMMENT '售后范围: 0-整单（历史数据/缺省，兼容旧语义）, 1-按商品（after_sale_item 有明细）'
        AFTER `refund_amount`;
