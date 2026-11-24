package com.ai.smartinventory.common;

import com.ai.smartinventory.dto.LoginDTO;
import com.ai.smartinventory.service.OperationLogService;
import com.ai.smartinventory.service.UserService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code OperationLogAspect} 的 P1-5 修复验证。
 *
 * 【背景】{@code operation_log} 实体早就有 {@code result} / {@code user_agent} 两列，
 * 但切面调用 {@code recordLog} 时从未传值 ⇒ 生产库里这两列**恒为 NULL**，
 * 事后审计既看不到「业务返回了什么」，也看不到「请求方是什么客户端」。
 *
 * 本测试锁定：写操作落库时必须带上 result 摘要与 userAgent；
 * 且失败路径（抛异常）下 result 应为 null、errorMsg 非空。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P1-5 操作日志补 result/userAgent")
class OperationLogAspectTest {

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private UserService userService;

    @Mock
    private ProceedingJoinPoint jp;

    @Mock
    private Signature signature;

    private OperationLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new OperationLogAspect(operationLogService, userService);
        when(jp.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("addProduct");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }


    /**
     * 在「静态 MerchantContext 打桩」的保护下执行切面。
     *
     * 【为什么需要】切面内部调用 {@code MerchantContext.currentUserId()} →
     * {@code StpUtil.isLogin()}，而后者在没有 Sa-Token 上下文的纯单测里会抛
     * 「未能获取有效的请求上下文」。打桩掉这个静态方法，即可把测试聚焦在
     * 「result / userAgent 是否被正确采集」这一 P1-5 关注点上。
     */
    private void runAround() throws Throwable {
        try (MockedStatic<MerchantContext> mc = Mockito.mockStatic(MerchantContext.class)) {
            mc.when(MerchantContext::currentUserId).thenReturn(1L);
            aspect.around(jp);
        }
    }

    private void runAroundExpectingThrow() throws Throwable {
        try (MockedStatic<MerchantContext> mc = Mockito.mockStatic(MerchantContext.class)) {
            mc.when(MerchantContext::currentUserId).thenReturn(1L);
            try {
                aspect.around(jp);
            } catch (Throwable expected) {
                // 切面必须原样上抛，日志失败不影响业务
            }
        }
    }

    private void bindRequest(String method, String uri, String userAgent) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (userAgent != null) {
            req.addHeader("User-Agent", userAgent);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("成功路径：result 摘要与 userAgent 均落库")
    void successPathCapturesResultAndUserAgent() throws Throwable {
        bindRequest("POST", "/api/product", "Mozilla/5.0 (TestBrowser)");
        when(jp.getArgs()).thenReturn(new Object[]{Map.of("name", "x")});
        Map<String, Object> body = Map.of("code", 200, "message", "操作成功");
        when(jp.proceed()).thenReturn(body);

        runAround();

        ArgumentCaptor<String> resultCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> uaCap = ArgumentCaptor.forClass(String.class);
        // 10 个固定参数 + result + userAgent ⇒ 用 verify 精确匹配末尾两参
        verify(operationLogService).recordLog(
                eq(1L), isNull(), eq("POST addProduct"), eq("POST /api/product"),
                anyString(), anyString(), eq(1), anyLong(), isNull(),
                resultCap.capture(), uaCap.capture());

        String capturedResult = resultCap.getValue();
        assertNotNull(capturedResult, "成功路径 result 不应为 null（P1-5 修复点）");
        assertTrue(capturedResult.contains("\"code\":200"),
                "result 应含返回体内容，实际：" + capturedResult);
        assertEquals("Mozilla/5.0 (TestBrowser)", uaCap.getValue(),
                "userAgent 应原样落库（P1-5 修复点）");
    }

    @Test
    @DisplayName("失败路径：result 为 null，errorMsg 有值")
    void failurePathLeavesResultNull() throws Throwable {
        bindRequest("DELETE", "/api/product/1", "curl/8.0");
        when(jp.getArgs()).thenReturn(new Object[]{1L});
        when(jp.proceed()).thenThrow(new IllegalStateException("boom"));

        runAroundExpectingThrow();

        ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultCap = ArgumentCaptor.forClass(String.class);
        verify(operationLogService).recordLog(
                eq(1L), isNull(), eq("DELETE addProduct"), eq("DELETE /api/product/1"),
                anyString(), anyString(), eq(0), anyLong(), errCap.capture(),
                resultCap.capture(), eq("curl/8.0"));

        assertNull(resultCap.getValue(), "失败路径不应写 result");
        assertTrue(errCap.getValue().contains("IllegalStateException"),
                "errorMsg 应含异常类名，实际：" + errCap.getValue());
    }

    @Test
    @DisplayName("读操作（GET）不进日志链路")
    void readOperationSkipped() throws Throwable {
        bindRequest("GET", "/api/product/list", "ua");
        when(jp.proceed()).thenReturn("ok");

        runAround();

        verify(operationLogService, org.mockito.Mockito.never())
                .recordLog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("敏感字段在 result 中被掩码（防新 token 泄漏进审计表）")
    void sensitiveResultMasked() throws Throwable {
        bindRequest("POST", "/api/auth/login", "ua");
        when(jp.getArgs()).thenReturn(new Object[0]);
        when(jp.proceed()).thenReturn(Map.of("code", 200, "token", "secret-token-value"));

        runAround();

        ArgumentCaptor<String> resultCap = ArgumentCaptor.forClass(String.class);
        verify(operationLogService).recordLog(
                eq(1L), isNull(), anyString(), anyString(),
                isNull(), anyString(), eq(1), anyLong(), isNull(),
                resultCap.capture(), anyString());

        String r = resultCap.getValue();
        assertNotNull(r);
        assertTrue(r.contains("***"), "token 应被掩码，实际：" + r);
        assertTrue(!r.contains("secret-token-value"), "明文 token 不得入日志表");
    }

    // =========================================================================
    // 【测试报告 P3-4 · 2026-10-11】日志管理里「登录」行的操作人为空
    //
    // 实测（沙箱真起 Spring Boot + MySQL 8.4 打 /api/auth/login）得到的地面真相：
    //   登录成功  → username = admin / user   （Sa-Token 在同一请求内已建会话，取得到）
    //   登录失败  → username = NULL  user_id = NULL
    //   注册      → username = NULL  user_id = NULL
    // 也就是说报告原文「登录发生在鉴权前，切面取不到当前用户」只对了一半：
    // 真正空掉的是**失败的登录与注册**——恰恰是最该追溯的两类记录。
    //
    // 下面两条把「未登录时必须从入参兜底出 username」钉死。
    // 一旦有人把 usernameFromArgs 兜底删掉，第一条立刻变红。
    // =========================================================================

    @Test
    @DisplayName("P3-4：未登录（登录失败/注册）时操作人从入参的 username 兜底")
    void anonymousWriteFallsBackToArgsUsername() throws Throwable {
        bindRequest("POST", "/api/auth/login", "curl/8.0");
        LoginDTO dto = new LoginDTO();
        dto.setUsername("baduser");
        dto.setPassword("WRONG");
        when(jp.getArgs()).thenReturn(new Object[]{dto});
        when(jp.proceed()).thenReturn(Map.of("code", 400, "message", "用户名或密码错误"));

        // 未登录 ⇒ currentUserId() 必须是 null，这是本条要模拟的真实场景
        try (MockedStatic<MerchantContext> mc = Mockito.mockStatic(MerchantContext.class)) {
            mc.when(MerchantContext::currentUserId).thenReturn(null);
            aspect.around(jp);
        }

        verify(operationLogService).recordLog(
                isNull(), eq("baduser"), eq("POST addProduct"), eq("POST /api/auth/login"),
                anyString(), anyString(), eq(1), anyLong(), isNull(), anyString(), anyString());
    }

    @Test
    @DisplayName("P3-4：入参里根本没有 username 时仍然落 null（不许瞎猜操作人）")
    void anonymousWriteWithoutUsernameStaysNull() throws Throwable {
        bindRequest("POST", "/api/auth/register", "curl/8.0");
        when(jp.getArgs()).thenReturn(new Object[]{Map.of("nickname", "n")});
        when(jp.proceed()).thenReturn(Map.of("code", 200));

        try (MockedStatic<MerchantContext> mc = Mockito.mockStatic(MerchantContext.class)) {
            mc.when(MerchantContext::currentUserId).thenReturn(null);
            aspect.around(jp);
        }

        verify(operationLogService).recordLog(
                isNull(), isNull(), anyString(), anyString(),
                anyString(), anyString(), eq(1), anyLong(), isNull(), anyString(), anyString());
    }

    @Test
    @DisplayName("P3-4：usernameFromArgs 只认非空字符串，且对无 getter 的入参安全")
    void usernameFromArgsIsSafeAndSelective() {
        LoginDTO blank = new LoginDTO();
        blank.setUsername("   ");
        LoginDTO real = new LoginDTO();
        real.setUsername("kb");
        assertEquals("kb", OperationLogAspect.usernameFromArgs(new Object[]{blank, real}),
                "应跳过空白值继续往后找，取到非空的 username");

        assertNull(OperationLogAspect.usernameFromArgs(null), "args 为 null 应返回 null");
        assertNull(OperationLogAspect.usernameFromArgs(new Object[]{}), "空数组应返回 null");
        assertNull(OperationLogAspect.usernameFromArgs(new Object[]{1L, null, "str"}),
                "没有 getUsername() 的入参不应抛异常，只返回 null");
        assertNull(OperationLogAspect.usernameFromArgs(new Object[]{new LoginDTO()}),
                "username 未赋值（null）时不得返回空串或乱猜");
    }
}
