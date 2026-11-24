package com.ai.smartinventory.service;

import com.ai.smartinventory.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserService extends IService<User> {
    
    User getUserByUsername(String username);
    
    boolean register(String username, String password, String email, String phone, String nickname, String role);
    
    boolean updatePassword(Long userId, String oldPassword, String newPassword);
    
    boolean checkPassword(String rawPassword, String encodedPassword);
    
    boolean createOrUpdateUser(String username, String password, String email, String phone, String nickname, String role);
    
    String encodePassword(String rawPassword);
}
