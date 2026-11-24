-- =============================================================================
-- 回填商品描述（2026-10-10）
--
-- 【为什么需要】
-- `init.sql` 的商品种子 INSERT 原本不含 `description` 列，于是 8 个演示商品的
-- description 全是 NULL —— 商品列表与商品详情页每一处都显示「暂无描述」，
-- 卡片上留着一行空位。init.sql 已补上描述，但那只对**新建库**生效；
-- 已经跑着的库要靠本脚本回填。
--
-- 【怎么用】（与 fix-schema-drift.sql 同一套纪律）
--   本文件**不写 USE**，必须显式带库名执行，否则会打到当前默认库上：
--     /usr/local/mysql/bin/mysql -uroot -p --default-character-set=utf8mb4 smart_inventory \
--       < backend/src/main/resources/db/backfill_product_description.sql
--
-- 【幂等】
--   每条 UPDATE 都带 `AND (description IS NULL OR description = '')` ——
--   只补空值，**绝不覆盖商家自己填过的描述**；重复执行是无害的空操作。
--   末尾附一段核对查询，跑完直接看「仍未描述的商品数」是否为 0。
-- =============================================================================

UPDATE `product` SET `description` = '经典配方碳酸饮料，330ml 易拉罐装，冰镇后口感更佳。'
  WHERE `name` = '可口可乐330ml' AND (`description` IS NULL OR `description` = '');

UPDATE `product` SET `description` = '天然饮用水，550ml 瓶装，日常饮用与随身携带都方便。'
  WHERE `name` = '农夫山泉550ml' AND (`description` IS NULL OR `description` = '');

UPDATE `product` SET `description` = 'A17 芯片搭配 4800 万像素主摄，支持操作按钮与 USB-C 接口，512GB 存储。'
  WHERE `name` = 'iphone15手机' AND (`description` IS NULL OR `description` = '');

UPDATE `product` SET `description` = '主动降噪真无线耳机，单次续航约 8 小时，支持无线充电与低延迟游戏模式。'
  WHERE `name` = '小米无线蓝牙耳机' AND (`description` IS NULL OR `description` = '');

UPDATE `product` SET `description` = '速干透气的运动短袖，弹力面料不易变形，适合跑步与健身穿着。'
  WHERE `name` = '男士运动T恤' AND (`description` IS NULL OR `description` = '');

UPDATE `product` SET `description` = '雪纺拼接收腰连衣裙，垂坠感好，通勤与出游都能穿。'
  WHERE `name` = '女士连衣裙' AND (`description` IS NULL OR `description` = '');

UPDATE `product` SET `description` = '激光导航扫拖一体机，支持 APP 定时清扫与自动回充，适合中小户型。'
  WHERE `name` = '智能扫地机器人' AND (`description` IS NULL OR `description` = '');

UPDATE `product` SET `description` = '当季红富士苹果，果肉脆甜，净重约 5 斤，产地直发。'
  WHERE `name` = '新鲜苹果5斤装' AND (`description` IS NULL OR `description` = '');

-- -----------------------------------------------------------------------------
-- 核对：期望「仍未描述的商品数 = 0」（新建库 + 新种子里本来就有值，这里同样成立）
-- -----------------------------------------------------------------------------
SELECT
  COUNT(*) AS `total_products`,
  SUM(CASE WHEN `description` IS NULL OR `description` = '' THEN 1 ELSE 0 END) AS `still_missing_description`
FROM `product`;
