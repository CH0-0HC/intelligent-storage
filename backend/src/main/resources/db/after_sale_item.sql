-- =============================================================================
-- 23. 售后申请明细表（按商品粒度）
-- -----------------------------------------------------------------------------
-- 【为什么需要这张表】
-- after_sale 只有 order_id（整单粒度）。混合订单下商家 A 能处理含自家商品的
-- 整单售后（含商家 B 的商品），属跨商户越权残余面（审计 P1-6）。
-- 本表把「本次售后到底退哪些商品、每样几件」落成数据，从而：
--   · 归属校验可以按商品粒度做（不再依赖 countOrderOwnedByMerchant 的「含」语义）；
--   · 库存回补可以按 item 的 quantity 精确回补，不再整单全量；
--   · 幂等键可以从「订单级」细化为「订单+商品级」。
--
-- 【兼容口径】item_scope=0（或本表无该售后单的行）时，售后单仍是整单语义，
-- 回补逻辑回退到「整单全量」，与改造前逐字一致 —— 这是 104 项端到端断言的基础。
--
-- ⚠️ 与 db/init.sql 第 23 节保持一致，改一处务必同步另一处。
-- =============================================================================

USE smart_inventory;

CREATE TABLE IF NOT EXISTS `after_sale_item` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '售后明细ID',
    `after_sale_id`  BIGINT        NOT NULL                COMMENT '所属售后单ID（after_sale.id）',
    `order_id`       BIGINT        NOT NULL                COMMENT '关联订单ID（冗余，便于按订单聚合/幂等）',
    `order_item_id`  BIGINT        NOT NULL                COMMENT '订单明细ID（order_item.id，快照来源）',
    `product_id`     BIGINT        NOT NULL                COMMENT '商品ID（冗余，库存回补/归属校验直接可用）',
    `merchant_id`    BIGINT        DEFAULT NULL            COMMENT '商品所属商家ID（申请时快照；NULL 表示商品已物理删除）',
    `quantity`       INT           NOT NULL DEFAULT 0      COMMENT '本次申请数量（>0，且不超过 order_item.quantity）',
    `amount`         DECIMAL(12,2) NOT NULL DEFAULT 0      COMMENT '本行金额 = order_item.price * quantity（服务端计算）',
    `remark`         VARCHAR(255)  DEFAULT NULL            COMMENT '备注（如需按商品说明原因）',
    `deleted`        TINYINT       DEFAULT 0               COMMENT '删除标记',
    `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 一行订单明细在同一张售后单里只能出现一次（防止重复请求把 quantity 记两遍）
    UNIQUE KEY `uk_sale_item` (`after_sale_id`, `order_item_id`),
    -- 按售后单取全部明细（详情页/处理页主力查询）
    INDEX `idx_after_sale_id` (`after_sale_id`),
    -- 按订单+商品聚合已退数量（申请时做「不可超退」校验）
    INDEX `idx_order_product` (`order_id`, `product_id`),
    -- 按商家筛「本商家该处理的明细」
    INDEX `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后申请明细表（按商品粒度）';
