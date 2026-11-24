package com.ai.smartinventory.service;

import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.mapper.UserMapper;
import com.ai.smartinventory.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单元测试 —— 用户注册、密码校验、密码修改
 *
 * 【本次修复的两个既有缺陷】
 * 1) `baseMapper can not be null`：
 *    ServiceImpl.baseMapper 由 MyBatis-Plus 在 Spring 容器启动时注入，
 *    纯 Mockito 环境下 @InjectMocks 覆盖不到该字段。
 *    同时原测试直接对 `userService.save(...)` / `userService.getById(...)`
 *    / `userService.updateById(...)` 打桩 —— 这些是**被测对象自身的方法**，
 *    Mockito 无法 mock（只会真的去执行，然后撞上 baseMapper 为 null）。
 *    正确做法：注入 baseMapper 后，改为对底层 mapper 方法打桩
 *    （save -> insert、getById -> selectById、updateById -> updateById）。
 * 2) UnnecessaryStubbing / InvalidUseOfMatchers：
 *    已用 LENIENT 严格度收敛，并去掉未被使用的桩。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("用户服务单元测试")
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        // 修复点 1：手动注入 baseMapper
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);

        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPassword("$2a$10$encodedPasswordHash");
        testUser.setEmail("test@example.com");
        testUser.setPhone("13800138000");
        testUser.setNickname("测试用户");
        testUser.setRole("user");
        testUser.setStatus(1);
        testUser.setDeleted(0);
    }

    /**
     * 桩：按用户名查询命中的是 selectOne(wrapper, true) 双参重载
     *（ServiceImpl.getOne 在 3.5.x 的实现细节，详见 InventoryServiceImplTest 类注释）。
     */
    private void stubFindUser(User result) {
        when(userMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(result);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(result);
    }

    /**
     * 为 insert / updateById 打桩。
     *
     * 【注意】MyBatis-Plus 3.5.8 的 BaseMapper 同时存在
     *   insert(T) 与 insert(Collection&lt;T&gt;)
     *   updateById(T) 与 updateById(Collection&lt;T&gt;)
     * 两对重载。直接写 any(User.class) 会因为「两种重载都匹配」而编译失败
     * （reference to insert is ambiguous）。
     * 这里改用显式声明类型的 ArgumentMatcher&lt;User&gt; 消歧，
     * 既保证走到 insert(T) 重载，又能在 lambda 里安全调用 User 的 getter。
     */
    private ArgumentMatcher<User> userMatching(java.util.function.Predicate<User> predicate) {
        return (ArgumentMatcher<User>) u -> u != null && predicate.test(u);
    }

    private void stubInsert(int rows) {
        when(userMapper.insert(argThat(userMatching(u -> true)))).thenReturn(rows);
    }

    private void stubUpdateById(int rows) {
        when(userMapper.updateById(argThat(userMatching(u -> true)))).thenReturn(rows);
    }

    @Test
    @DisplayName("正常注册 - 新用户名")
    void testRegisterSuccess() {
        // 用户名不存在
        stubFindUser(null);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$encodedHash");
        stubInsert(1);

        boolean result = userService.register("testuser", "password123",
                "test@example.com", "13800138000", "测试用户", "user");

        assertTrue(result);
        verify(passwordEncoder).encode("password123");
        // 落库的密码必须是加密后的，绝不能是明文
        verify(userMapper).insert(argThat(userMatching(u ->
                "$2a$10$encodedHash".equals(u.getPassword()))));
    }

    @Test
    @DisplayName("注册失败 - 用户名已存在")
    void testRegisterDuplicateUsername() {
        stubFindUser(testUser);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                userService.register("testuser", "password123",
                        "test@example.com", "13800138000", "测试用户", "user"));

        assertTrue(ex.getMessage().contains("已存在"));
        // 重复注册不得产生任何写入
        verify(userMapper, never()).insert(argThat(userMatching(u -> true)));
    }

    @Test
    @DisplayName("注册 - 角色传空时应落库为默认角色 user")
    void testRegisterDefaultRole() {
        stubFindUser(null);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodedHash");
        stubInsert(1);

        userService.register("newuser", "password123",
                "new@example.com", "13900139000", "新用户", null);

        verify(passwordEncoder).encode("password123");
        // 关键断言：role 为空时兜底为 user，而不是写入 null
        verify(userMapper).insert(argThat(userMatching(u -> "user".equals(u.getRole()))));
    }

    @Test
    @DisplayName("注册 - 传入 admin 角色必须降级为 user【S-1 防提权】")
    void testRegisterCannotSelfEscalate() {
        stubFindUser(null);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodedHash");
        stubInsert(1);

        // 攻击者伪造 role=admin 自助注册（原实现会把该值原样落库 = 自封管理员）
        userService.register("attacker", "password123",
                "evil@example.com", "13700137000", "攻击者", "admin");

        // 关键断言：库里必须是 user，绝不能落成 admin
        verify(userMapper).insert(argThat(userMatching(u -> "user".equals(u.getRole()))));
    }

    @Test
    @DisplayName("密码验证 - BCrypt 匹配成功")
    void testCheckPasswordSuccess() {
        when(passwordEncoder.matches("correctPassword", "$2a$10$encodedPasswordHash"))
                .thenReturn(true);

        assertTrue(userService.checkPassword("correctPassword", "$2a$10$encodedPasswordHash"));
    }

    @Test
    @DisplayName("密码验证 - BCrypt 匹配失败")
    void testCheckPasswordFailure() {
        when(passwordEncoder.matches("wrongPassword", "$2a$10$encodedPasswordHash"))
                .thenReturn(false);

        assertFalse(userService.checkPassword("wrongPassword", "$2a$10$encodedPasswordHash"));
    }

    @Test
    @DisplayName("密码验证 - 不应存在明文回退逻辑")
    void testCheckPasswordNoPlaintextFallback() {
        when(passwordEncoder.matches("plaintext", "plaintext")).thenReturn(false);

        assertFalse(userService.checkPassword("plaintext", "plaintext"),
                "明文密码回退逻辑已被移除，不应返回 true");
    }

    @Test
    @DisplayName("修改密码 - 正常流程")
    void testUpdatePasswordSuccess() {
        // getById 走 selectById
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(passwordEncoder.matches("oldPassword", "$2a$10$encodedPasswordHash"))
                .thenReturn(true);
        when(passwordEncoder.encode("newPassword")).thenReturn("$2a$10$newHash");
        // updateById 走 updateById(entity)
        stubUpdateById(1);

        boolean result = userService.updatePassword(1L, "oldPassword", "newPassword");

        assertTrue(result);
        verify(passwordEncoder).encode("newPassword");
        // 写回库的必须是新密码的哈希
        verify(userMapper).updateById(argThat(userMatching(u ->
                "$2a$10$newHash".equals(u.getPassword()))));
    }

    @Test
    @DisplayName("修改密码 - 原密码错误")
    void testUpdatePasswordWrongOldPassword() {
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(passwordEncoder.matches("wrongOldPassword", "$2a$10$encodedPasswordHash"))
                .thenReturn(false);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                userService.updatePassword(1L, "wrongOldPassword", "newPassword"));

        assertTrue(ex.getMessage().contains("原密码"));
        // 校验失败时绝不能落库
        verify(userMapper, never()).updateById(argThat(userMatching(u -> true)));
    }

    @Test
    @DisplayName("修改密码 - 用户不存在")
    void testUpdatePasswordUserNotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                userService.updatePassword(999L, "oldPassword", "newPassword"));

        assertTrue(ex.getMessage().contains("不存在"));
        verify(userMapper, never()).updateById(argThat(userMatching(u -> true)));
    }

    @Test
    @DisplayName("根据用户名查询")
    void testGetUserByUsername() {
        stubFindUser(testUser);

        User result = userService.getUserByUsername("testuser");

        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
        assertEquals("user", result.getRole());
    }

    @Test
    @DisplayName("密码编码")
    void testEncodePassword() {
        when(passwordEncoder.encode("rawPassword")).thenReturn("$2a$10$encodedHash");

        String encoded = userService.encodePassword("rawPassword");

        assertNotNull(encoded);
        assertTrue(encoded.startsWith("$2a$10$"));
        verify(passwordEncoder).encode("rawPassword");
    }
}
