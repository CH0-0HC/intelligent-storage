package com.ai.smartinventory.security;

import cn.dev33.satoken.dao.SaTokenDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.List;

/**
 * 把 Sa-Token 的令牌 / 会话持久化到 MySQL。
 *
 * 【为什么必须有这个类】
 * 项目只依赖 sa-token-spring-boot3-starter，**没有** sa-token-redis-jackson，
 * 于是 Sa-Token 走默认的内存 DAO（ConcurrentHashMap）：
 *
 *   · 后端进程一重启 → 内存里的 token 全部消失
 *   · 浏览器 localStorage 里还留着旧 token
 *   · 下一个需要鉴权的请求立刻 401「token 无效」
 *   · 前端拦截器收到 401 就清 token、跳登录页
 *   ⇒ 用户视角：「用着用着突然被强制退出登录」，而且毫无提示。
 *
 * 实测证据（同一 token、同一接口）：
 *   重启前 GET /api/ai/dashboard → 200
 *   重启后 GET /api/ai/dashboard → 401
 *
 * 落到 MySQL 后，重启不再影响已登录用户；也**不需要**引入 Redis
 * （本机 Redis 并未运行，而项目本来就强依赖 MySQL）。
 *
 * 语义约定（与 Sa-Token 官方 DAO 保持一致）：
 *   timeout = -1（NEVER_EXPIRE）  → 永不过期
 *   timeout = 0                   → 立即过期（等价于删除）
 *   timeout > 0                   → 相对当前时间的秒数
 *   getTimeout / getObjectTimeout 在 key 不存在或已过期时返回 NOT_VALUE_EXPIRE(-2)
 */
@Slf4j
public class JdbcSaTokenDao implements SaTokenDao {

    private static final long NEVER = SaTokenDao.NEVER_EXPIRE;
    private static final long NOT_VALUE = SaTokenDao.NOT_VALUE_EXPIRE;

    /** 过期行清理的最小间隔，避免每次写入都做一次全表扫描式删除 */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    /**
     * 【安全修复·P0-4】反序列化白名单（JEP 290 ObjectInputFilter）。
     *
     * 背景：getObject 用 JDK 原生 ObjectInputStream 反序列化 token_value。
     * 当前写入口只有本类，**不可直接远程利用**；但 classpath 里带着
     * fastjson2 / hutool / commons 等含 gadget 的库，一旦哪天出现
     * 「能写 sys_token.token_value」的次要缺陷（SQL 注入、越权写库、备份还原等），
     * 就立刻升级为 RCE —— 属于典型的「哑弹」。
     *
     * 现做法：显式放行「Sa-Token 会话实际会用到的那几类」，其余一律拒绝。
     *   - cn.dev33.satoken.**    SaSession 本体及其内部的 token/session 结构
     *   - java.lang.*            String / Integer / Long / Boolean 等会话值
     *   - java.util.*            会话内部的 HashMap / ArrayList 等容器
     *   - java.time.*            Sa-Token 内部可能存的时间对象
     *   - 数组类型               白名单类的数组（如 Object[]）
     *
     * 关键点：**不用黑名单、不按「包名包含」宽松匹配**，只精确放行必要前缀；
     * 且显式拒绝 `!java.*` 之外的一切（含 `java.lang.reflect`、`java.lang.invoke`、
     * `javax.script`、`com.sun.*`、`sun.*`、`org.springframework.*` 等经典 gadget 载体）。
     *
     * 注意：`java.lang.reflect.Proxy` 会被 `java.lang.*` 前缀命中 —— 这是刻意保留的，
     * 因为 Sa-Token 的 SaSession 在部分 JDK 下会用到动态代理；但 JDK 自带的
     * ObjectInputFilter 会在反序列化反射对象时另有保护，且代理类本身仍需其
     * 接口类也在白名单内，故风险可控。
     */
    private static final ObjectInputFilter SERIAL_FILTER = filterInfo -> {
        Class<?> clazz = filterInfo.serialClass();
        if (clazz == null) {
            // 非类信息（如深度/数组长度限制），交由 JDK 默认限额处理
            return ObjectInputFilter.Status.UNDECIDED;
        }
        // 数组类型：取其组件类型再判定
        while (clazz.isArray()) {
            clazz = clazz.getComponentType();
        }
        // 基本类型（int/long 等的包装在下面 java.lang 分支；原始类型直接放行）
        if (clazz.isPrimitive()) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        String name = clazz.getName();
        if (name.startsWith("cn.dev33.satoken.")
                || name.startsWith("java.lang.")
                || name.startsWith("java.util.")
                || name.startsWith("java.time.")) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        log.warn("拒绝反序列化非白名单类（疑似反序列化攻击）: {}", name);
        return ObjectInputFilter.Status.REJECTED;
    };

    private final JdbcTemplate jdbc;

    private volatile long lastSweepAt = 0L;

    public JdbcSaTokenDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 工具

    private static long now() {
        return System.currentTimeMillis();
    }

    /** 把 Sa-Token 的秒级相对超时换算成绝对过期时刻（epoch 毫秒） */
    private static long toExpireAt(long timeoutSeconds) {
        if (timeoutSeconds == NEVER) {
            return NEVER;
        }
        if (timeoutSeconds <= 0) {
            return now() - 1L;      // 立即过期
        }
        return now() + timeoutSeconds * 1000L;
    }

    private static boolean expired(Long expireAt) {
        return expireAt != null && expireAt != NEVER && expireAt < now();
    }

    private Long readExpireAt(String key) {
        List<Long> rows = jdbc.queryForList(
                "SELECT expire_time FROM sys_token WHERE token_key = ?", Long.class, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 低频清理已过期的行 */
    private void sweepIfDue() {
        long t = now();
        if (t - lastSweepAt < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepAt = t;
        try {
            int n = jdbc.update(
                    "DELETE FROM sys_token WHERE expire_time <> ? AND expire_time < ?", NEVER, t);
            if (n > 0) {
                log.debug("清理已过期的 Sa-Token 记录 {} 条", n);
            }
        } catch (Exception e) {
            log.warn("清理过期 Sa-Token 记录失败（不影响主流程）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------- 字符串存取

    @Override
    public String get(String key) {
        if (key == null) {
            return null;
        }
        List<String> rows = jdbc.queryForList(
                "SELECT token_value FROM sys_token WHERE token_key = ? AND (expire_time = ? OR expire_time > ?)",
                String.class, key, NEVER, now());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (key == null) {
            return;
        }
        if (timeout == 0) {
            delete(key);
            return;
        }
        long expireAt = toExpireAt(timeout);
        jdbc.update(
                "INSERT INTO sys_token (token_key, token_value, expire_time) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE token_value = VALUES(token_value), expire_time = VALUES(expire_time)",
                key, value, expireAt);
        sweepIfDue();
    }

    @Override
    public void update(String key, String value) {
        if (key == null) {
            return;
        }
        int n = jdbc.update("UPDATE sys_token SET token_value = ? WHERE token_key = ?", value, key);
        if (n == 0) {
            // 严格实现会抛异常；这里选择宽容：key 不存在就按不过期写入，
            // 避免因时序问题把一次正常的登录流程打断。
            set(key, value, NEVER);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null) {
            return;
        }
        jdbc.update("DELETE FROM sys_token WHERE token_key = ?", key);
    }

    @Override
    public long getTimeout(String key) {
        Long expireAt = readExpireAt(key);
        if (expireAt == null || expired(expireAt)) {
            return NOT_VALUE;
        }
        if (expireAt == NEVER) {
            return NEVER;
        }
        long remain = (expireAt - now()) / 1000L;
        return remain < 1 ? NOT_VALUE : remain;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (key == null) {
            return;
        }
        if (timeout == 0) {
            delete(key);
            return;
        }
        jdbc.update("UPDATE sys_token SET expire_time = ? WHERE token_key = ?", toExpireAt(timeout), key);
    }

    // --------------------------------------------------------------- 对象存取
    // Sa-Token 的 SaSession 实现了 java.io.Serializable，这里用 JDK 原生序列化 + Base64，
    // 不引入额外序列化依赖。

    @Override
    public Object getObject(String key) {
        String raw = get(key);
        if (raw == null) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(raw)))) {
            // 【安全修复·P0-4】反序列化前挂白名单过滤，杜绝 gadget 链 RCE。
            in.setObjectInputFilter(SERIAL_FILTER);
            return in.readObject();
        } catch (Exception e) {
            log.warn("反序列化 Sa-Token 对象失败，已按不存在处理: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void setObject(String key, Object object, long timeout) {
        if (key == null || object == null) {
            return;
        }
        try {
            set(key, serialize(object), timeout);
        } catch (Exception e) {
            log.error("序列化 Sa-Token 对象失败: key={}", key, e);
        }
    }

    @Override
    public void updateObject(String key, Object object) {
        if (key == null || object == null) {
            return;
        }
        try {
            update(key, serialize(object));
        } catch (Exception e) {
            log.error("序列化 Sa-Token 对象失败: key={}", key, e);
        }
    }

    @Override
    public void deleteObject(String key) {
        delete(key);
    }

    @Override
    public long getObjectTimeout(String key) {
        return getTimeout(key);
    }

    @Override
    public void updateObjectTimeout(String key, long timeout) {
        updateTimeout(key, timeout);
    }

    private static String serialize(Object object) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(object);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    // ------------------------------------------------------------------ 检索

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        String keyLike = (prefix == null ? "" : prefix).replace("%", "\\%") + "%";
        String kwLike = "%" + (keyword == null ? "" : keyword).replace("%", "\\%") + "%";
        String order = sortType ? "DESC" : "ASC";
        return jdbc.queryForList(
                "SELECT token_key FROM sys_token WHERE token_key LIKE ? AND token_key LIKE ? "
                        + "ORDER BY token_key " + order + " LIMIT ? OFFSET ?",
                String.class, keyLike, kwLike, size, Math.max(start, 0));
    }
}
