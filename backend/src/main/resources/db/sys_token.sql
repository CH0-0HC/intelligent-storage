-- =============================================================================
-- Sa-Token 会话/令牌持久化表
-- =============================================================================
-- 【为什么需要这张表】
-- 项目依赖里只有 sa-token-spring-boot3-starter，**没有** sa-token-redis-jackson，
-- 因此 Sa-Token 默认使用内存 DAO（SaTokenDaoDefaultImpl，一个 ConcurrentHashMap）。
-- 后果：**后端进程一重启，所有已登录用户的 token 全部作废**。
-- 表现：浏览器 localStorage 里还留着旧 token，下一个需要鉴权的请求立刻返回 401，
--       前端 axios 拦截器随即清 token 并跳登录页 —— 用户看到的是
--       「用着用着突然被强制退出登录」，且完全不知道原因。
--       （实测：重启前 200 的 token，重启后同一 token 直接 401）
--
-- 本表把 token 与会话落到 MySQL（项目本来就强依赖它），重启后会话继续有效，
-- 同时不需要引入 Redis 依赖（本机 Redis 并未运行）。
--
-- 由 JdbcSaTokenDao 读写，不要手工修改本表内容。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `sys_token` (
  `token_key`   VARCHAR(255)  NOT NULL                COMMENT 'Sa-Token 的 key（token 值 / session id / 其它）',
  `token_value` LONGTEXT      NOT NULL                COMMENT '值：普通字符串原样存；对象为 Java 序列化后的 Base64',
  `expire_time` BIGINT        NOT NULL DEFAULT -1     COMMENT '过期时刻（epoch 毫秒）；-1 表示永不过期',
  PRIMARY KEY (`token_key`),
  KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sa-Token 会话与令牌持久化表';
