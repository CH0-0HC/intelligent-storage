-- =============================================================================
-- 回填商品销量 product.sales（2026-10-11）
--
-- 【为什么需要】
-- 第四轮把 `product.sales` 绑定到「发货/签收扣库存」的时机上增量累加
-- （OrderServiceImpl#deductStockForOrder → ProductMapper#increaseSales），
-- 只对**新增**交易生效。存量演示订单没走过那条增量路径 ——
-- 于是 8 个商品 sales 全是 0，首页「热门商品」与商品列表「热门推荐」角标下的
-- `ORDER BY sales DESC` 在全 0 时等于没排序（测试报告 P3-1）。
--
-- 【口径：净销量，与运行时逐条对齐】
-- 运行时 product.sales 只有三个写入点：
--   +  OrderServiceImpl#deductStockForOrder         发货/签收扣库存时 +qty
--   -  OrderServiceImpl#cancelOrder                 已扣库存的订单被取消时 -qty
--   -  AfterSaleServiceImpl#restoreStockForReturn   退货退款完成回补库存时 -qty
--
--   「已扣过库存」在订单状态上的表现就是 2 已发货 / 3 已完成；
--   取消那条把订单改成了 4 已取消；退货那条订单状态仍是 3 但货退了。
--   所以净销量 = Σ qty(订单状态 ∈ {2,3}) − Σ qty(已完成的退货入库流水)
--
-- 【为什么订单侧用 order 状态、而不是 inventory_log 的出库流水】
-- 两者在正常数据下等价，但手工出入库接口（InventoryController /in /out）
-- 的 `type` 是**客户端可传**的，传成 "order" 就会往 inventory_log 里写一条
-- type=1 + order_type='order' 的流水，而它并不会改 sales。
-- 以订单状态为准可以天然免疫这种污染。
-- 退货侧没有同类问题（after_sale 流水只能由真实退货产生），故按流水取精确回补量。
--
-- 【怎么用】（与 backfill_product_description.sql 同一套纪律）
--   本文件**不写 USE**，必须显式带库名执行：
--     mysql --default-character-set=utf8mb4 smart_inventory \
--       < backend/src/main/resources/db/backfill_product_sales.sql
--
-- 【幂等】
--   绝对值 SET（不是累加），重复执行结果不变；只改 `deleted = 0` 的商品。
--   末尾附核对查询：expected_sales 与实际 sales 必须逐行相等、差异行数为 0。
--
-- 【兼容性】
--   全程按 `only_full_group_by` 书写（外层对每个非聚合列都显式 SUM），
--   在 MySQL 5.7+/8.x 默认 sql_mode 下都能跑。
-- =============================================================================

UPDATE `product` p
LEFT JOIN (
    SELECT t.product_id AS product_id,
           GREATEST(COALESCE(SUM(t.shipped_qty), 0) - COALESCE(SUM(t.returned_qty), 0), 0) AS net_sales
    FROM (
        SELECT oi.product_id AS product_id,
               SUM(oi.quantity) AS shipped_qty,
               0 AS returned_qty
        FROM `order_item` oi
        INNER JOIN `order` o
                ON o.id = oi.order_id
               AND o.deleted = 0
               AND o.order_status IN (2, 3)
        WHERE oi.deleted = 0
        GROUP BY oi.product_id
        UNION ALL
        SELECT il.product_id AS product_id,
               0 AS shipped_qty,
               SUM(il.quantity) AS returned_qty
        FROM `inventory_log` il
        WHERE il.deleted = 0
          AND il.type = 0
          AND il.order_type = 'after_sale'
        GROUP BY il.product_id
    ) t
    GROUP BY t.product_id
) s ON s.product_id = p.id
SET p.sales       = COALESCE(s.net_sales, 0),
    p.update_time = NOW()
WHERE p.deleted = 0;

-- -----------------------------------------------------------------------------
-- 核对 1：逐商品比对「按口径算出的期望值」与「实际写进去的值」，期望 diff 全为 0
-- -----------------------------------------------------------------------------
SELECT p.id,
       p.name,
       p.sales AS actual_sales,
       COALESCE(s.expected_sales, 0) AS expected_sales,
       p.sales - COALESCE(s.expected_sales, 0) AS diff
FROM `product` p
LEFT JOIN (
    SELECT t.product_id AS product_id,
           GREATEST(COALESCE(SUM(t.shipped_qty), 0) - COALESCE(SUM(t.returned_qty), 0), 0) AS expected_sales
    FROM (
        SELECT oi.product_id AS product_id,
               SUM(oi.quantity) AS shipped_qty,
               0 AS returned_qty
        FROM `order_item` oi
        INNER JOIN `order` o
                ON o.id = oi.order_id
               AND o.deleted = 0
               AND o.order_status IN (2, 3)
        WHERE oi.deleted = 0
        GROUP BY oi.product_id
        UNION ALL
        SELECT il.product_id AS product_id,
               0 AS shipped_qty,
               SUM(il.quantity) AS returned_qty
        FROM `inventory_log` il
        WHERE il.deleted = 0
          AND il.type = 0
          AND il.order_type = 'after_sale'
        GROUP BY il.product_id
    ) t
    GROUP BY t.product_id
) s ON s.product_id = p.id
WHERE p.deleted = 0
ORDER BY p.id;

-- -----------------------------------------------------------------------------
-- 核对 2：一行结论 —— 期望 diff_rows = 0
-- -----------------------------------------------------------------------------
SELECT COUNT(*) AS products_checked,
       SUM(CASE WHEN actual_sales <> expected_sales THEN 1 ELSE 0 END) AS diff_rows
FROM (
    SELECT COALESCE(p.sales, 0) AS actual_sales,
           COALESCE(s.expected_sales, 0) AS expected_sales
    FROM `product` p
    LEFT JOIN (
        SELECT t.product_id AS product_id,
               GREATEST(COALESCE(SUM(t.shipped_qty), 0) - COALESCE(SUM(t.returned_qty), 0), 0) AS expected_sales
        FROM (
            SELECT oi.product_id AS product_id,
                   SUM(oi.quantity) AS shipped_qty,
                   0 AS returned_qty
            FROM `order_item` oi
            INNER JOIN `order` o
                    ON o.id = oi.order_id
                   AND o.deleted = 0
                   AND o.order_status IN (2, 3)
            WHERE oi.deleted = 0
            GROUP BY oi.product_id
            UNION ALL
            SELECT il.product_id AS product_id,
                   0 AS shipped_qty,
                   SUM(il.quantity) AS returned_qty
            FROM `inventory_log` il
            WHERE il.deleted = 0
              AND il.type = 0
              AND il.order_type = 'after_sale'
            GROUP BY il.product_id
        ) t
        GROUP BY t.product_id
    ) s ON s.product_id = p.id
    WHERE p.deleted = 0
) x;
