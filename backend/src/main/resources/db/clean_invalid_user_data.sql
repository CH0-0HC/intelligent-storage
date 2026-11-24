-- ============================================================================
-- clean_invalid_user_data.sql —— 存量脏数据清洗（测试报告#1 的第 ③ 项）
--
-- 【为什么需要】补齐校验之前，系统里已经落了一批非法数据：
--   · sys_user.phone      = 'abc'                       —— 注册时手机号无格式校验
--   · order.shipping_phone = '1234567890987'（13 位）等  —— 下单时收货电话无校验
-- 这些行对**新数据**已无影响（新数据分别被 RegisterDTO 的 @Pattern、
-- OrderCreateDTO 的 @Pattern 拦住），但会一直以脏数据形式出现在
-- 管理员「用户管理」和商家「订单管理」里，成为误导性的展示与统计来源。
--
-- 【执行方式】本文件**不会被应用自动执行**（项目未启用 spring.sql.init），
-- 必须人工执行，并且**先只跑第 1 节的只读报告**确认影响面，再决定是否执行清洗：
--   mysql -u<user> -p smart_inventory \
--     < backend/src/main/resources/db/clean_invalid_user_data.sql
--
-- 【安全边界 · 请务必读完】
--   第 1 节 只读 SELECT，随时可跑，不改任何数据。
--   第 2 节 UPDATE sys_user.phone：个人资料字段，非法值置 NULL。
--            不删账号、不碰密码、不碰邮箱，随时可回滚（跑之前先记下第 1 节的结果）。
--   第 3 节 订单收货电话**默认只报告不修改** —— order 是交易凭证，
--            静默改写历史单据在审计上说不通。确需更正请按订单逐条人工处理；
--            真要批量改，把末尾那段 UPDATE 的注释去掉，自行留档后再跑。
--
--   密码无法清洗：库里存的是 BCrypt 哈希，看不出现文，无法判定哪些是弱口令。
--   只能靠新的注册/改密校验防止再产生。若要处理存量弱口令，
--   需另定策略（强制改密 / 重置邮件），不在本脚本范围内。
--
-- 【正则口径】^1[3-9][0-9]{9}$ —— 与 RegisterDTO、OrderCreateDTO、
--   前端 utils/phonePattern.js 完全一致，三处同源，避免再出两套口径。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. 只读报告：先看清楚有多少行、都是谁
-- ----------------------------------------------------------------------------
SELECT 'sys_user.phone 非法' AS 检查项, COUNT(*) AS 行数
FROM sys_user
WHERE phone IS NOT NULL
  AND phone <> ''
  AND phone NOT REGEXP '^1[3-9][0-9]{9}$';

SELECT id, username, nickname, email, phone, create_time
FROM sys_user
WHERE phone IS NOT NULL
  AND phone <> ''
  AND phone NOT REGEXP '^1[3-9][0-9]{9}$'
ORDER BY id;

SELECT 'order.shipping_phone 非法' AS 检查项, COUNT(*) AS 行数
FROM `order`
WHERE shipping_phone IS NOT NULL
  AND shipping_phone <> ''
  AND shipping_phone NOT REGEXP '^1[3-9][0-9]{9}$';

SELECT id, order_no, user_id, shipping_name, shipping_phone, order_status, create_time
FROM `order`
WHERE shipping_phone IS NOT NULL
  AND shipping_phone <> ''
  AND shipping_phone NOT REGEXP '^1[3-9][0-9]{9}$'
ORDER BY id;


-- ----------------------------------------------------------------------------
-- 2. 清洗 sys_user.phone（确认第 1 节结果后再执行）
--    非法值置 NULL：管理员用户列表里不再出现 "abc"，也不影响登录与下单。
-- ----------------------------------------------------------------------------
START TRANSACTION;

UPDATE sys_user
SET phone = NULL
WHERE phone IS NOT NULL
  AND phone <> ''
  AND phone NOT REGEXP '^1[3-9][0-9]{9}$';

-- 复核：本查询必须返回 0 行；不是 0 就 ROLLBACK，别 COMMIT
SELECT COUNT(*) AS 仍非法的手机号行数
FROM sys_user
WHERE phone IS NOT NULL
  AND phone <> ''
  AND phone NOT REGEXP '^1[3-9][0-9]{9}$';

COMMIT;


-- ----------------------------------------------------------------------------
-- 3. 订单收货电话：只报告，默认不改
-- ----------------------------------------------------------------------------
-- 如确需批量更正历史单据，先人工核对下面这段的结果，再取消注释执行。
-- 注意：会改写交易凭证上的字段，务必先备份：
--   mysqldump -u<user> -p smart_inventory `order` > order_backup_$(date +%F).sql
--
-- START TRANSACTION;
-- UPDATE `order`
-- SET shipping_phone = NULL
-- WHERE shipping_phone IS NOT NULL
--   AND shipping_phone <> ''
--   AND shipping_phone NOT REGEXP '^1[3-9][0-9]{9}$';
-- SELECT COUNT(*) AS 仍非法的收货电话行数 FROM `order`
-- WHERE shipping_phone IS NOT NULL
--   AND shipping_phone <> ''
--   AND shipping_phone NOT REGEXP '^1[3-9][0-9]{9}$';
-- COMMIT;
