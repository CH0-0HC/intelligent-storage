-- 同步商品和库存表数据
USE smart_inventory;

-- 1. 对于已经有库存记录的商品，将 inventory.quantity 同步到 product.stock
UPDATE product p 
JOIN inventory i ON p.id = i.product_id 
SET p.stock = i.quantity 
WHERE p.deleted = 0 AND i.deleted = 0;

-- 2. 对于没有库存记录的商品，创建库存记录并将 product.stock 同步到 inventory.quantity
INSERT INTO inventory (product_id, quantity, warning_quantity, create_time, update_time)
SELECT id, stock, 10, NOW(), NOW()
FROM product 
WHERE deleted = 0 
AND id NOT IN (SELECT product_id FROM inventory WHERE deleted = 0);

-- 显示同步结果
SELECT 
    '同步完成' AS status,
    COUNT(*) AS total_products,
    SUM(CASE WHEN i.id IS NOT NULL THEN 1 ELSE 0 END) AS products_with_inventory,
    SUM(CASE WHEN i.id IS NULL THEN 1 ELSE 0 END) AS new_inventory_created
FROM product p
LEFT JOIN inventory i ON p.id = i.product_id AND i.deleted = 0
WHERE p.deleted = 0;

-- 显示商品和库存的详细信息
SELECT 
    p.id AS product_id,
    p.name AS product_name,
    p.stock AS product_stock,
    i.quantity AS inventory_quantity,
    CASE WHEN p.stock = i.quantity THEN '一致' ELSE '不一致' END AS sync_status
FROM product p
LEFT JOIN inventory i ON p.id = i.product_id AND i.deleted = 0
WHERE p.deleted = 0
ORDER BY p.id;
