-- =============================================================================
-- AI 连接配置表（商家自助配置 Ollama 地址 / 模型）
-- -----------------------------------------------------------------------------
-- 【为什么建这张表】
-- 此前 Ollama 的服务地址与模型名只能写在 application.yml 里，商家在界面上
-- 没有任何配置入口；换台算力服务器或换个模型，都必须改配置 + 重启后端。
-- 本表把配置下沉为运行时数据，保存后立即生效。
--
-- 【merchant_id 语义】
--   0        → 全局默认配置（由管理员维护，所有商户的兜底）
--   > 0      → 商户级配置，值等于该商家账号的 sys_user.id
-- 解析顺序：商户行 → 全局行 → application.yml(ai.local.*) 内置默认值。
--
-- 【为什么不加逻辑删除】
--   merchant_id 上有唯一索引，若使用 deleted 逻辑删除，
--   「删除后再保存同一 merchant_id」会命中唯一键冲突（本项目 cart 表踩过同款坑）。
--   「恢复默认」实现为物理删除该行。
--
-- ⚠️ 本文件与 db/init.sql 末尾的第 21 节保持一致，改一处务必同步另一处。
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
