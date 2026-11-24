package com.ai.smartinventory.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JdbcSaTokenDao 反序列化白名单（P0-4）单元测试。
 *
 * 【为什么要专门测这个】
 * getObject 用 JDK 原生 ObjectInputStream 读 DB 里的 token_value。
 * 修复前没有 ObjectInputFilter ⇒ 只要有人能写 sys_token.token_value，
 * 就能塞一条 gadget 序列化流触发 RCE（classpath 含 fastjson2/hutool）。
 * 修复后必须能证明：① 非白名单类被拒绝；② Sa-Token 真实会话数据仍能正常往返。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Sa-Token DAO 反序列化白名单")
class JdbcSaTokenDaoTest {

    @Mock
    private JdbcTemplate jdbc;

    private JdbcSaTokenDao dao;

    @BeforeEach
    void setUp() {
        dao = new JdbcSaTokenDao(jdbc);
    }

    /** 把任意可序列化对象编成 DAO 期望的 Base64 串 */
    private static String encode(Serializable obj) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(obj);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    /** 让 dao.get(key) 返回指定的 Base64 原文 */
    private void stubRaw(String key, String raw) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(key), anyLong(), anyLong()))
                .thenReturn(raw == null ? List.of() : List.of(raw));
    }

    // ---------------- 正向：白名单内的会话数据必须能正常往返 ----------------

    @Test
    @DisplayName("白名单内：Sa-Token 会话结构（HashMap + String）能正常反序列化")
    void testAllowedSessionRoundTrip() throws Exception {
        Map<String, Object> session = new HashMap<>();
        session.put("userRole", "merchant");
        session.put("userId", 2L);
        session.put("flags", new ArrayList<>(List.of("a", "b")));

        stubRaw("satoken:login:session:2", encode((Serializable) session));

        Object got = dao.getObject("satoken:login:session:2");

        assertNotNull(got, "白名单内的会话数据必须能读回，否则会导致用户莫名掉线");
        assertInstanceOf(Map.class, got);
        assertEquals("merchant", ((Map<?, ?>) got).get("userRole"));
    }

    @Test
    @DisplayName("白名单内：纯 String 值能正常反序列化")
    void testAllowedStringRoundTrip() throws Exception {
        stubRaw("k", encode("hello"));
        assertEquals("hello", dao.getObject("k"));
    }

    // ---------------- 负向：非白名单类必须被拒绝 ----------------

    @Test
    @DisplayName("★ 非白名单类被拒绝：com.sun.* 等 gadget 载体不得反序列化")
    void testRejectNonWhitelistedClass() throws Exception {
        // 用一个「可序列化但不在白名单」的自定义类模拟 gadget。
        // 注意：必须能在同一 JVM 内序列化 + 反序列化才能走到 filter 判定，
        // 所以这里用自定义类而非真实的 fastjson gadget（后者未必在 classpath）。
        stubRaw("evil", encode(new NotAllowedGadget("payload")));

        Object got = dao.getObject("evil");

        assertNull(got, "非白名单类必须被 ObjectInputFilter 拒绝，返回 null 而不是实例");
    }

    @Test
    @DisplayName("★ 基类在白名单、但子类不在：拒绝（防子类绕过）")
    void testRejectSubclassBypass() throws Exception {
        // 这个类继承 Serializable，但包名不在任何白名单前缀内
        stubRaw("evil2", encode(new EvilContainer()));
        assertNull(dao.getObject("evil2"));
    }

    @Test
    @DisplayName("非法 Base64 / 非序列化内容：捕获异常并返回 null（不抛到上层）")
    void testMalformedPayloadReturnsNull() {
        stubRaw("bad", "!!!not-base64!!!");
        assertNull(dao.getObject("bad"));
    }

    @Test
    @DisplayName("key 不存在：直接返回 null，不触碰反序列化")
    void testMissingKey() {
        stubRaw("nope", null);
        assertNull(dao.getObject("nope"));
    }

    // ---------------- 测试辅助类型 ----------------

    /** 故意放在测试包下（不在白名单前缀内）的可序列化类 —— 模拟 gadget */
    static class NotAllowedGadget implements Serializable {
        private final String cmd;
        NotAllowedGadget(String cmd) { this.cmd = cmd; }
    }

    /** 另一类干扰项 */
    static class EvilContainer implements Serializable {
        private final Object inner = Map.of("x", "y");
    }
}
