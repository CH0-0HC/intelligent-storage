-- 创建数据库
CREATE DATABASE IF NOT EXISTS smart_inventory DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE smart_inventory;

-- 1. 用户表
CREATE TABLE `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码',
    `nickname` VARCHAR(100) COMMENT '昵称',
    `real_name` VARCHAR(100) COMMENT '真实姓名',
    `phone` VARCHAR(20) COMMENT '手机号',
    `email` VARCHAR(100) COMMENT '邮箱',
    `avatar` VARCHAR(255) COMMENT '头像',
    `gender` TINYINT DEFAULT 0 COMMENT '性别: 0-未知, 1-男, 2-女',
    `role` VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色: user-普通用户, merchant-商家, admin-管理员',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
    `last_login_time` DATETIME COMMENT '最后登录时间',
    `last_login_ip` VARCHAR(50) COMMENT '最后登录IP',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记: 0-未删除, 1-已删除',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_username` (`username`),
    INDEX `idx_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 2. 商品分类表
CREATE TABLE `product_category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `name` VARCHAR(100) NOT NULL COMMENT '分类名称',
    `parent_id` BIGINT DEFAULT 0 COMMENT '父分类ID',
    `level` TINYINT DEFAULT 1 COMMENT '层级',
    `sort` INT DEFAULT 0 COMMENT '排序',
    `icon` VARCHAR(255) COMMENT '图标',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- 3. 商品表
CREATE TABLE `product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `name` VARCHAR(200) NOT NULL COMMENT '商品名称',
    `category_id` BIGINT NOT NULL COMMENT '分类ID',
    `merchant_id` BIGINT COMMENT '商家ID',
    `price` DECIMAL(10,2) NOT NULL COMMENT '售价',
    `cost` DECIMAL(10,2) COMMENT '成本价',
    `stock` INT DEFAULT 0 COMMENT '库存数量',
    `unit` VARCHAR(20) DEFAULT '件' COMMENT '单位',
    `image` VARCHAR(500) COMMENT '商品图片',
    `images` TEXT COMMENT '商品图片列表(JSON)',
    `description` TEXT COMMENT '商品描述',
    `specs` TEXT COMMENT '规格参数(JSON)',
    `tags` VARCHAR(500) COMMENT '标签',
    `sales` INT DEFAULT 0 COMMENT '销量',
    `rating` DECIMAL(3,2) DEFAULT 5.00 COMMENT '评分',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-下架, 1-上架',
    `is_hot` TINYINT DEFAULT 0 COMMENT '是否热门',
    `is_recommend` TINYINT DEFAULT 0 COMMENT '是否推荐',
    -- 【2026-09-28 新增】商品级退货退款规则（商家上架前设置，购买界面可见）
    --   退货时效此前写死在 AfterSaleRule.WINDOW_DAYS=15，所有商品一刀切；
    --   现下沉为商品属性：商家上架时选 7/15/30 天或自定义天数。
    --   0 = 该商品不支持退货退款（仅「质量问题」等有理由售后仍可走客服裁定）。
    --   默认 15 = 与改造前的全站口径保持一致（存量商品语义不变）。
    `return_window_days` INT NOT NULL DEFAULT 15 COMMENT '退货退款期限(天)，0-不支持退货退款；默认15',
    -- 是否支持「无理由」退货：0 表示只接受有理由（质量/破损/不符等）申请。
    `return_no_reason` TINYINT NOT NULL DEFAULT 1 COMMENT '是否支持无理由退货: 0-不支持, 1-支持',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_category_id` (`category_id`),
    INDEX `idx_merchant_id` (`merchant_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_is_hot` (`is_hot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 4. 供应商表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `supplier` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商ID',
    `name` VARCHAR(200) NOT NULL COMMENT '供应商名称',
    `contact` VARCHAR(50) COMMENT '联系人',
    `phone` VARCHAR(20) COMMENT '联系电话',
    `address` VARCHAR(255) COMMENT '地址',
    `remark` TEXT COMMENT '备注',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商表';

-- 5. 采购单表
-- ⚠️ 本表曾与 PurchaseOrder.java 实体严重不一致（历史坑，2026-09-20 修复）
--
-- 症状：商家「新增采购订单」必报
--   java.sql.SQLSyntaxErrorException: Unknown column 'supplier_name' in 'field list'
-- 根因：本文件原设计只有 supplier_id BIGINT NOT NULL，没有
--   supplier_name / merchant_id / audit_remark；而实体与 PurchaseOrderController
--   用的是 supplierName / merchantId / auditRemark，且插入时从不提供 supplier_id。
--   ⇒ 即使补上 supplier_name，下一步仍会因 supplier_id NOT NULL 继续失败。
-- 说明：另一份 backend/src/main/resources/db/purchase_order.sql 里的定义
--   **本来就是对的**（与实体一致），是 init.sql 这一份走偏了 —— 两份建库脚本
--   长期互斥。现已把两者与线上库结构对齐。
-- supplier_id / operator_id / audit_id / audit_time 为旧设计遗留字段，
--   代码已不再写入，保留为可空列待后续迁移；不要直接删列。
CREATE TABLE `purchase_order` (
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

-- 6. 采购明细表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `purchase_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    `purchase_id` BIGINT NOT NULL COMMENT '采购单ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `product_name` VARCHAR(200) COMMENT '商品名称(冗余)',
    `quantity` INT NOT NULL COMMENT '采购数量',
    `price` DECIMAL(10,2) NOT NULL COMMENT '采购单价',
    `amount` DECIMAL(12,2) NOT NULL COMMENT '金额',
    `actual_quantity` INT DEFAULT 0 COMMENT '实际入库数量',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_purchase_id` (`purchase_id`),
    INDEX `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购明细表';

-- 7. 入库单表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `stock_in` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '入库单ID',
    `order_no` VARCHAR(50) NOT NULL UNIQUE COMMENT '入库单号',
    `purchase_id` BIGINT COMMENT '关联采购单ID',
    `type` TINYINT DEFAULT 0 COMMENT '入库类型: 0-采购入库, 1-退货入库, 2-盘盈入库',
    `total_quantity` INT DEFAULT 0 COMMENT '总数量',
    `total_amount` DECIMAL(12,2) DEFAULT 0 COMMENT '总金额',
    `status` TINYINT DEFAULT 0 COMMENT '状态: 0-待入库, 1-已完成',
    `operator_id` BIGINT COMMENT '操作员ID',
    `remark` TEXT COMMENT '备注',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单表';

-- 8. 入库明细表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `stock_in_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    `stock_in_id` BIGINT NOT NULL COMMENT '入库单ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `quantity` INT NOT NULL COMMENT '入库数量',
    `price` DECIMAL(10,2) COMMENT '入库单价',
    `remark` VARCHAR(255) COMMENT '备注',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_stock_in_id` (`stock_in_id`),
    INDEX `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库明细表';

-- 9. 出库单表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `stock_out` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '出库单ID',
    `order_no` VARCHAR(50) NOT NULL UNIQUE COMMENT '出库单号',
    `type` TINYINT DEFAULT 0 COMMENT '出库类型: 0-销售出库, 1-退货出库, 2-报损出库',
    `total_quantity` INT DEFAULT 0 COMMENT '总数量',
    `status` TINYINT DEFAULT 0 COMMENT '状态: 0-待出库, 1-已完成',
    `operator_id` BIGINT COMMENT '操作员ID',
    `remark` TEXT COMMENT '备注',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库单表';

-- 10. 出库明细表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `stock_out_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    `stock_out_id` BIGINT NOT NULL COMMENT '出库单ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `quantity` INT NOT NULL COMMENT '出库数量',
    `remark` VARCHAR(255) COMMENT '备注',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_stock_out_id` (`stock_out_id`),
    INDEX `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库明细表';

-- 11. 库存表
CREATE TABLE `inventory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '库存ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `quantity` INT DEFAULT 0 COMMENT '当前库存',
    `warning_quantity` INT DEFAULT 10 COMMENT '预警库存',
    `last_in_time` DATETIME COMMENT '最后入库时间',
    `last_out_time` DATETIME COMMENT '最后出库时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_id` (`product_id`),
    INDEX `idx_quantity` (`quantity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表';

-- 12. 库存记录表
CREATE TABLE `inventory_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `type` TINYINT NOT NULL COMMENT '类型: 0-入库, 1-出库',
    `quantity` INT NOT NULL COMMENT '变动数量',
    `before_quantity` INT COMMENT '变动前库存',
    `after_quantity` INT COMMENT '变动后库存',
    `order_type` VARCHAR(50) COMMENT '关联单据类型',
    `order_id` BIGINT COMMENT '关联单据ID',
    `remark` VARCHAR(255) COMMENT '备注',
    `operator_id` BIGINT COMMENT '操作员ID',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_product_id` (`product_id`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存记录表';

-- 13. 购物车表
-- 【2026-09-22】新增 selected 列以支持「勾选商品结算」。
-- 此前本表只有 quantity，没有承载勾选状态的字段 —— 于是前端购物车页
-- 根本没有勾选框，结算必然是全量下单（等价于强制全选）。
-- 关键：勾选状态**存在服务端**，刷新页面 / 重新登录后仍能保持。
-- 默认 1（新加入购物车的商品默认勾选），与主流电商一致。
-- 线上库已用 ALTER TABLE 同步：
--   ALTER TABLE cart ADD COLUMN selected TINYINT NOT NULL DEFAULT 1
--     COMMENT '是否选中: 1-选中, 0-未选中' AFTER quantity;
CREATE TABLE `cart` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '购物车ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `quantity` INT NOT NULL DEFAULT 1 COMMENT '数量',
    `selected` TINYINT NOT NULL DEFAULT 1 COMMENT '是否选中: 1-选中, 0-未选中',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- 14. 订单表
-- ⚠️ 本表曾与 Order.java 实体漂移（历史坑，2026-09-20 修复）
--   实体有 logisticsCompany / trackingNo / shipTime（物流三件套），
--   而本文件与线上表都没有这三列 —— 于是**所有订单查询**都报
--     Unknown column 'logistics_company' in 'field list'
--   表现为：结算后跳「待付款」列表 500，被误判成「结算失败」。
--   注意：实体新增字段后，本文件与线上库必须同步补列，
--   否则「查询」会直接挂掉（MyBatis-Plus 默认 SELECT 全字段）。
--   同类问题已出现三次（purchase_order、order），改实体务必回头核 schema。
CREATE TABLE `order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no` VARCHAR(50) NOT NULL UNIQUE COMMENT '订单号',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `total_amount` DECIMAL(12,2) NOT NULL COMMENT '总金额',
    `discount_amount` DECIMAL(12,2) DEFAULT 0 COMMENT '优惠金额',
    `pay_amount` DECIMAL(12,2) NOT NULL COMMENT '实付金额',
    `pay_status` TINYINT DEFAULT 0 COMMENT '支付状态: 0-未支付, 1-已支付',
    `pay_time` DATETIME COMMENT '支付时间',
    `order_status` TINYINT DEFAULT 0 COMMENT '订单状态: 0-待付款, 1-待发货, 2-待收货, 3-已完成, 4-已取消',
    `shipping_name` VARCHAR(100) COMMENT '收货人姓名',
    `shipping_phone` VARCHAR(20) COMMENT '收货人电话',
    `shipping_address` VARCHAR(255) COMMENT '收货地址',
    `remark` TEXT COMMENT '备注',
    `cancel_time` DATETIME COMMENT '取消时间',
    `cancel_reason` VARCHAR(30) DEFAULT NULL COMMENT '取消原因: AUTO_TIMEOUT-超时未付款自动取消, USER-用户取消, MERCHANT-商家关闭',
    `complete_time` DATETIME COMMENT '完成时间',
    `logistics_company` VARCHAR(100) COMMENT '物流公司',
    `tracking_no` VARCHAR(100) COMMENT '物流单号',
    `ship_time` DATETIME COMMENT '发货时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_order_no` (`order_no`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`order_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 15. 订单明细表
CREATE TABLE `order_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    `order_id` BIGINT NOT NULL COMMENT '订单ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `product_name` VARCHAR(200) COMMENT '商品名称(冗余)',
    `product_image` VARCHAR(255) COMMENT '商品图片(冗余)',
    `price` DECIMAL(10,2) NOT NULL COMMENT '单价',
    `quantity` INT NOT NULL COMMENT '数量',
    `amount` DECIMAL(12,2) NOT NULL COMMENT '金额',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_order_id` (`order_id`),
    INDEX `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 16. 用户行为表(用于AI推荐)
CREATE TABLE `user_behavior` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `behavior_type` VARCHAR(20) NOT NULL COMMENT '行为类型: view-浏览, cart-加购, purchase-购买, collect-收藏',
    `rating` INT COMMENT '评分(1-5)',
    `session_id` VARCHAR(100) COMMENT '会话ID',
    `ip` VARCHAR(50) COMMENT 'IP地址',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_product_id` (`product_id`),
    INDEX `idx_behavior_type` (`behavior_type`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为表';

-- 17. AI分析记录表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `ai_analysis_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `analysis_type` VARCHAR(50) NOT NULL COMMENT '分析类型: inventory_warning-库存预警, sales_forecast-销量预测, report-经营报告',
    `merchant_id` BIGINT COMMENT '商家ID',
    `input_params` TEXT COMMENT '输入参数(JSON)',
    `output_result` TEXT COMMENT '输出结果(JSON)',
    `ai_model` VARCHAR(50) COMMENT '使用的AI模型',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-失败, 1-成功',
    `error_msg` TEXT COMMENT '错误信息',
    `operator_id` BIGINT COMMENT '操作员ID',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_analysis_type` (`analysis_type`),
    INDEX `idx_merchant_id` (`merchant_id`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI分析记录表';

-- 18. 系统配置表
-- 预留 / 未使用（无对应实体）
CREATE TABLE `system_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    `config_key` VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    `config_value` TEXT COMMENT '配置值',
    `config_name` VARCHAR(100) COMMENT '配置名称',
    `config_type` VARCHAR(20) DEFAULT 'string' COMMENT '类型: string, number, boolean, json',
    `remark` VARCHAR(255) COMMENT '备注',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 19. 操作日志表
CREATE TABLE `operation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `user_id` BIGINT COMMENT '操作用户ID',
    `username` VARCHAR(50) COMMENT '用户名',
    `operation` VARCHAR(100) COMMENT '操作',
    `method` VARCHAR(200) COMMENT '方法名',
    `params` TEXT COMMENT '参数',
    `result` TEXT COMMENT '返回结果',
    `ip` VARCHAR(50) COMMENT 'IP地址',
    `user_agent` VARCHAR(500) COMMENT 'UserAgent',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-失败, 1-成功',
    `error_msg` TEXT COMMENT '错误信息',
    `execute_time` BIGINT COMMENT '执行时间(ms)',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- 初始化数据
-- 登录页（frontend/src/views/login/Login.vue）的演示按钮填的就是下面这组口令，
-- 请保持三者一致；改口令务必同步改本文件与登录页提示。
--
-- ⚠️ 历史坑（2026-09-20 修复）：此前三个哈希全是错的 ——
--   · admin 存的是 "123456" 的哈希，与注释写的 admin123 不符，点演示按钮必然失败；
--   · merchant / user 的哈希长度是 61（正确为 60），BCrypt 直接判定
--     "does not look like BCrypt"，**任何口令都登录不了**。
-- 现全部替换为现场用 BCryptPasswordEncoder 生成并回验过的有效哈希。
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `role`, `status`) VALUES
('admin', '$2a$10$M/aKXFmSb.AWGAeFlWvd.OFviJvslId3zDNhQ3pksuafIq/QbCYYy', '系统管理员', 'admin', 1);

-- 插入测试商家账号 (密码: merchant123)
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `role`, `status`) VALUES
('merchant', '$2a$10$6U6CMt5TCNHoiLfZC4Pv6.Pz9H1jaD2UxlzoZZ.r2wSxGW5T2y7ai', '测试商家', 'merchant', 1);

-- 插入测试用户账号 (密码: user123)
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `role`, `status`) VALUES
('user', '$2a$10$H6aoeulS7DhgmIlosOooiuaba5Zwd8yFJZ/sdCmNw2Yan9YqE.is2', '测试用户', 'user', 1);

-- 插入测试客服账号（【2026-09-28 新增角色】密码: kefu123）
--   为什么必须有 kefu 这个角色：商品退货期限改为商家按商品设置后，超期买家
--   需「先联系客服、由客服依据商家统一标准裁定」。裁定人必须独立于商家 ——
--   让商家自己裁定等于让被申请方裁决自己是否该退货，中立性不成立。
--   客服是平台方角色，不绑定商户（currentMerchantId 返回 null），
--   只能操作 service_ticket 一条链路，不继承 admin 的全局写权限。
--
--   ⚠️ 该哈希为**现场生成并回验**（BCryptPasswordEncoder.encode("kefu123")
--      → matches() 复验 true，长度 60），不是手编的 —— 本项目曾因手写 BCrypt
--      哈希导致三个种子账号全部登不进去（根因与复盘见《项目交接文档-2026-10-10.md》§4.4）。
--      生成方式见 scripts/GenHash.java（附在仓库里，便于其它账号复用同一手法）。
--      上线前请连同其它三个演示账号一起改掉。
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `role`, `status`) VALUES
('kefu', '$2a$10$cErRGAiZyiRFCwGwl5151.Acx3HwICctrsy97V8J75DnBYgu33Pp6', '平台客服', 'kefu', 1);

-- 插入商品分类
INSERT INTO `product_category` (`name`, `parent_id`, `level`, `sort`, `status`) VALUES
('食品饮料', 0, 1, 1, 1),
('数码电子', 0, 1, 2, 1),
('服装鞋包', 0, 1, 3, 1),
('家居日用', 0, 1, 4, 1),
('生鲜水果', 0, 1, 5, 1);

-- 插入测试商品
-- 【2026-10-10 修复】原 INSERT 不含 description 列，8 个商品的 description 全为 NULL，
-- 于是商品列表与详情页每一处都显示「暂无描述」，卡片上留着一行空位。
-- 这里补上种子描述；**已有数据的库不会自动变**，另配
-- db/backfill_product_description.sql 做幂等回填（只补空值，不覆盖已有描述）。
INSERT INTO `product` (`name`, `category_id`, `merchant_id`, `price`, `cost`, `stock`, `unit`, `status`, `is_hot`, `is_recommend`, `description`) VALUES
('可口可乐330ml', 1, 2, 3.00, 2.00, 500, '瓶', 1, 1, 1, '经典配方碳酸饮料，330ml 易拉罐装，冰镇后口感更佳。'),
('农夫山泉550ml', 1, 2, 2.00, 0.80, 1000, '瓶', 1, 1, 1, '天然饮用水，550ml 瓶装，日常饮用与随身携带都方便。'),
('iphone15手机', 2, 2, 5999.00, 4500.00, 50, '部', 1, 1, 1, 'A17 芯片搭配 4800 万像素主摄，支持操作按钮与 USB-C 接口，512GB 存储。'),
('小米无线蓝牙耳机', 2, 2, 199.00, 120.00, 200, '个', 1, 1, 0, '主动降噪真无线耳机，单次续航约 8 小时，支持无线充电与低延迟游戏模式。'),
('男士运动T恤', 3, 2, 89.00, 45.00, 300, '件', 1, 0, 1, '速干透气的运动短袖，弹力面料不易变形，适合跑步与健身穿着。'),
('女士连衣裙', 3, 2, 199.00, 95.00, 150, '件', 1, 1, 1, '雪纺拼接收腰连衣裙，垂坠感好，通勤与出游都能穿。'),
('智能扫地机器人', 4, 2, 1299.00, 800.00, 80, '台', 1, 1, 1, '激光导航扫拖一体机，支持 APP 定时清扫与自动回充，适合中小户型。'),
('新鲜苹果5斤装', 5, 2, 29.90, 15.00, 200, '箱', 1, 1, 1, '当季红富士苹果，果肉脆甜，净重约 5 斤，产地直发。');


-- =============================================================================
-- 20. Sa-Token 会话/令牌持久化表
-- -----------------------------------------------------------------------------
-- 【为什么建这张表】
-- 依赖里只有 sa-token-spring-boot3-starter，没有 sa-token-redis-jackson，
-- 于是 Sa-Token 默认用内存 DAO（ConcurrentHashMap）——**后端一重启，
-- 所有登录态全部作废**。浏览器 localStorage 里还留着旧 token，下一个
-- 需要鉴权的请求立刻 401，前端拦截器随即清 token 跳登录页，
-- 用户看到的就是「用着用着突然被强制退出登录」。
--
-- 实测：同一 token、同一接口，重启前 200，重启后 401。
-- 建本表后由 JdbcSaTokenDao 落库，重启不再影响已登录用户，
-- 且无需引入 Redis（本机 Redis 未运行）。
--
-- ⚠️ 与 db/sys_token.sql 保持一致（那份是单独补表用的便捷脚本）。
-- =============================================================================
CREATE TABLE IF NOT EXISTS `sys_token` (
  `token_key`   VARCHAR(255)  NOT NULL                COMMENT 'Sa-Token 的 key（token 值 / session id / 其它）',
  `token_value` LONGTEXT      NOT NULL                COMMENT '值：普通字符串原样存；对象为 Java 序列化后的 Base64',
  `expire_time` BIGINT        NOT NULL DEFAULT -1     COMMENT '过期时刻（epoch 毫秒）；-1 表示永不过期',
  PRIMARY KEY (`token_key`),
  KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sa-Token 会话与令牌持久化表';

-- =============================================================================
-- 21. AI 连接配置表（商家自助配置 Ollama 地址 / 模型）
-- -----------------------------------------------------------------------------
-- 【为什么建这张表】
-- 此前 Ollama 地址与模型名只能写在 application.yml，商家在界面上没有任何
-- 配置入口；换算力服务器或换模型都必须改配置 + 重启后端。本表把配置下沉为
-- 运行时数据，保存后立即生效（AiConfigServiceImpl 每次调用实时读取）。
--
-- 【merchant_id 语义】
--   0   → 全局默认配置（管理员维护，所有商户的兜底）
--   > 0 → 商户级配置，值等于该商家账号的 sys_user.id
-- 解析顺序：商户行 → 全局行 → application.yml(ai.local.*) 内置默认值。
--
-- 【为什么不加逻辑删除】merchant_id 上有唯一索引，用 deleted 逻辑删除会让
-- 「删除后再保存同一 merchant_id」触发唯一键冲突（cart 表踩过同款坑）。
-- 「恢复默认」实现为物理删除该行。
--
-- ⚠️ 与 db/ai_config.sql 保持一致，改一处务必同步另一处。
-- =============================================================================
CREATE TABLE IF NOT EXISTS `ai_config` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    `merchant_id`     BIGINT       NOT NULL                COMMENT '商家ID；0 表示全局默认配置',
    `base_url`        VARCHAR(255) NOT NULL                COMMENT 'Ollama 服务地址，如 http://localhost:11434',
    `model`           VARCHAR(100) NOT NULL                COMMENT '模型名称，如 qwen3.5:0.8b',
    `num_ctx`         INT          DEFAULT 4096            COMMENT '上下文窗口长度；过大(如256K)会超出显存导致CPU混合推理',
    `num_predict`     INT          DEFAULT 1024            COMMENT '单次生成最大token数',
    `temperature`     DECIMAL(3,2) DEFAULT 0.70            COMMENT '采样温度 0~2',
    `timeout_seconds` INT          DEFAULT 120             COMMENT '单次请求超时(秒)',
    `enabled`         TINYINT      DEFAULT 1               COMMENT '是否启用本地AI: 0-停用, 1-启用',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI连接配置表';

-- =============================================================================
-- 22. 售后申请单表
-- -----------------------------------------------------------------------------
-- 【为什么建这张表】
-- 缺陷背景：系统此前只有「订单」域，**完全没有售后域** —— 没有表、没有实体、
-- 没有接口、前端也没有任何按钮。用户确认收货（order_status = 3）后，
-- 「我的订单」页面只渲染「再次购买」，页面上不存在申请售后入口。
-- 根因是「该功能从未实现」，而不是「入口被某个条件挡住了」。
--
-- 【设计口径：对齐淘宝 / 京东】
--   · 入口有效期：交易成功（确认收货）后 15 天内可申请售后
--   · 7 天无理由：仅退款 / 退货退款 中的「无理由」原因仅限确认收货后 7 天内
--   · 并发限制：同一订单同一时刻只允许一个进行中（待处理 / 已同意）的售后单
--   · 被拒绝 / 已撤销后：剩余时效内允许重新申请，故 order_id 上**不加唯一索引**
--     （唯一索引 + 逻辑删除 = 本项目已踩过的坑，见 cart 表 uk_user_product）
--
-- 【无 merchant_id 列的原因】
-- 与 order 表同理：一个订单可能含多个商家的商品，售后单无法挂单一 merchant_id。
-- 商家侧过滤统一走「order → order_item → product.merchant_id 反查」，
-- 与本项目既有范式（OrderMapper.selectOrderIdsByMerchantId）保持同一路径。
--
-- ⚠️ 与 db/after_sale.sql 保持一致，改一处务必同步另一处。
-- =============================================================================
CREATE TABLE IF NOT EXISTS `after_sale` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '售后单ID',
    `after_sale_no` VARCHAR(50)  NOT NULL                COMMENT '售后单号（唯一）',
    `order_id`      BIGINT       NOT NULL                COMMENT '关联订单ID',
    `order_no`      VARCHAR(50)  DEFAULT NULL            COMMENT '订单号（冗余，便于展示）',
    `user_id`       BIGINT       NOT NULL                COMMENT '申请用户ID（买家）',
    `type`          TINYINT      NOT NULL DEFAULT 1      COMMENT '售后类型: 1-仅退款, 2-退货退款, 3-换货',
    `reason_code`   VARCHAR(30)  NOT NULL                COMMENT '原因编码: no_reason-7天无理由, quality-质量问题, mismatch-与描述不符, damaged-破损少件, late-未按约定时间发货, other-其他',
    `reason`        VARCHAR(200) DEFAULT NULL            COMMENT '原因文案（服务端按编码生成）',
    `description`   VARCHAR(500) DEFAULT NULL            COMMENT '买家补充说明',
    `images`        TEXT         DEFAULT NULL            COMMENT '凭证图片（JSON 数组）',
    `refund_amount` DECIMAL(12,2) NOT NULL DEFAULT 0     COMMENT '申请退款金额；换货为 0',
    `item_scope`    TINYINT      NOT NULL DEFAULT 0      COMMENT '售后范围: 0-整单（历史数据/缺省，兼容旧语义）, 1-按商品（after_sale_item 有明细）',
    -- 【2026-09-28 新增】客服裁定放行凭证：超期后经客服同意才允许提交售后，
    -- 提交时把那张客服工单的 id 写在这里，作为「本次超期放行」的可追溯依据。
    -- NULL = 常规申请（在商品退货期限内），未经过客服。
    `cs_ticket_id`  BIGINT       DEFAULT NULL            COMMENT '客服裁定工单ID（超期放行凭证）；NULL-常规时效内申请',
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
-- ⚠️ 与 db/after_sale_item.sql 保持一致，改一处务必同步另一处。
-- =============================================================================
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

-- =============================================================================
-- 24. 客服裁定工单表（超期售后的唯一放行通道）
-- -----------------------------------------------------------------------------
-- 【为什么需要这张表】
-- 改造前：售后时效是**全站写死**的 15 天（AfterSaleRule.WINDOW_DAYS），
-- 超过即 eligible=false，用户在任何界面都只能看到一个置灰按钮，
-- 没有任何「还能不能争取一下」的通道，客服在系统里也无处受理。
--
-- 改造后（本表 + kefu 角色）：
--   · 商家上架商品时设置该商品支持的退货退款期限（product.return_window_days）；
--   · 期限内的商品，用户照旧直接申请（不经过本表）；
--   · 期限外的商品，用户**先提交客服工单**（本表，status=0 待裁定）；
--     客服依据「商家为商品指定的统一标准」裁定：
--       resolution=1 允许售后 → 用户获得一次性超期放行授权，此后才能提交售后；
--       resolution=2 驳回     → 工单关闭，用户可重新联系客服说明新情况。
--
-- 【为什么不复用 after_sale 表加个状态】
-- after_sale 承载的是「钱和库存真要动」的单据，其状态机（待处理/已同意/…）
-- 直接驱动退款与库存回补。把「尚未受理的咨询」塞进去，会让商家售后列表、
-- 待处理计数、库存幂等闸门全部看到不该出现的中间态。裁定与售后是**先后**
-- 两个阶段，用两张表反而让状态机各自干净。
--
-- 【授权怎么落地】
-- 裁定通过后用户提交售后时，服务端把本工单 id 写入 after_sale.cs_ticket_id，
-- 并校验「工单属于本人 + 属于本订单 + 已裁定通过 + 尚未被使用过」。
-- 因此该工单是**一次性**放行凭证：用过一次后再次申请会被拒，
-- 避免「一张工单反复刷超期售后」。
--
-- 【merchant_id 为什么冗余存一份】
-- 与 after_sale_item 同理：客服裁定要按商家口径执行，而 order → order_item →
-- product.merchant_id 的实时反查在商品被改归属/删除后会漂移。申请时快照一份，
-- 工单归属即固定不变（product_id 为 NULL 时为整单咨询，merchant_id 取 NULL）。
--
-- ⚠️ 与 db/service_ticket.sql 保持一致，改一处务必同步另一处。
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
    -- 该工单的放行授权是否已被用掉（写入 after_sale.cs_ticket_id 后置 1）。
    -- 与 after_sale.cs_ticket_id 是「一对一」关系，双重保证一张工单只放行一次。
    `consumed`         TINYINT      NOT NULL DEFAULT 0      COMMENT '放行授权是否已使用: 0-未使用, 1-已使用',
    `deleted`          TINYINT      DEFAULT 0               COMMENT '删除标记',
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    -- 订单列表/售后申请页按订单查「我有没有可用的放行工单」
    INDEX `idx_order_id` (`order_id`),
    INDEX `idx_user_id` (`user_id`),
    -- 客服工作台按状态筛待办
    INDEX `idx_status` (`status`),
    INDEX `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服裁定工单表（超期售后放行通道）';

