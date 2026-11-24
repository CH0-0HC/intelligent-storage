package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.entity.User;
import com.ai.smartinventory.mapper.UserMapper;
import com.ai.smartinventory.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import java.util.Set;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 【安全修复·S-1】自助注册允许的角色白名单。
     *
     * <p>注册是**匿名**入口，role 来自请求体、可被任意篡改。原实现把 role 原样落库，
     * 导致 {@code POST /api/auth/register {"role":"admin"}} 即可自封管理员。
     * 现只放行「买家 user / 商家 merchant」两种自助角色（与注册页下拉选项一致），
     * 其余任何值（admin / kefu / 乱填）一律降级为 user。
     * 管理员 / 客服只能由后台（{@link #createOrUpdateUser}）分配。
     */
    private static final Set<String> SELF_REGISTER_ROLES = Set.of("user", "merchant");

    @Resource
    private PasswordEncoder passwordEncoder;
    
    @Resource
    private UserMapper userMapper;
    
    @Override
    public User getUserByUsername(String username) {
        return this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
    }
    
    @Override
    public boolean register(String username, String password, String email, String phone, String nickname, String role) {
        if (getUserByUsername(username) != null) {
            throw new RuntimeException("用户名已存在");
        }
        
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setPhone(phone);
        user.setNickname(nickname != null ? nickname : username);
        // 【安全修复·S-1】不再原样落库 role，必须过自助注册白名单（详见 SELF_REGISTER_ROLES）。
        // 注意先判空：Set.of(...).contains(null) 会抛 NPE（role 缺省时为 null，见 UserServiceImplTest#testRegisterDefaultRole）。
        user.setRole(role != null && SELF_REGISTER_ROLES.contains(role) ? role : "user");
        user.setStatus(1);
        user.setDeleted(0);
        
        return this.save(user);
    }
    
    @Override
    public boolean updatePassword(Long userId, String oldPassword, String newPassword) {
        User user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("原密码错误");
        }
        // 【测试报告#1·口径一致】注册侧（RegisterDTO @Size(min=6)）和前端 Password.vue
        // 都要求 ≥6 位，唯独服务端这条路径没有任何长度约束 —— 绕过前端直接调接口，
        // 就能把密码改成 1 位。三处现在同一口径。
        if (newPassword == null || newPassword.length() < 6) {
            throw new RuntimeException("密码长度不能少于6位");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        return this.updateById(user);
    }
    
    @Override
    public boolean checkPassword(String rawPassword, String encodedPassword) {
        // 使用BCrypt进行密码验证
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
    
    @Override
    public boolean createOrUpdateUser(String username, String password, String email, String phone, String nickname, String role) {
        User user = getUserByUsername(username);
        
        if (user != null) {
            // 更新现有用户，只更新需要的字段
            LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(User::getId, user.getId())
                    .set(User::getPassword, passwordEncoder.encode(password))
                    .set(User::getEmail, email)
                    .set(User::getPhone, phone)
                    .set(User::getNickname, nickname != null ? nickname : username)
                    .set(User::getRole, role != null && !role.isEmpty() ? role : "user")
                    .set(User::getStatus, 1);
            return userMapper.update(null, updateWrapper) > 0;
        } else {
            // 创建新用户
            user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setEmail(email);
            user.setPhone(phone);
            user.setNickname(nickname != null ? nickname : username);
            user.setRole(role != null && !role.isEmpty() ? role : "user");
            user.setStatus(1);
            user.setDeleted(0);
            return this.save(user);
        }
    }
    
    @Override
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
