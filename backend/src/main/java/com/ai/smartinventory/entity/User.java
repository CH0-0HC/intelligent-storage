package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class User {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String username;
    
    // 【修复】BCrypt 密码哈希此前会随实体一起被序列化返回 —— 实测 GET /api/user/list
    // 的响应体里带着 admin 的 password 哈希（$2a$10$...）。虽然 BCrypt 不可逆，
    // 但配合 admin123 / merchant123 这类弱口令，离线爆破成本极低。
    //
    // 这里用 WRITE_ONLY（只写入、不输出）而不是 @JsonIgnore：
    //   - @JsonIgnore 是**双向**忽略，会让 POST /user/add / PUT /user/update 的
    //     请求体解析不到 password，新增用户或重置密码时密码会静默变成 null；
    //   - WRITE_ONLY 只禁止序列化（响应不带 password），反序列化（请求体读入）仍然正常。
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    
    private String nickname;
    
    private String realName;
    
    private String phone;
    
    private String email;
    
    private String avatar;
    
    private Integer gender;
    
    private String role;
    
    private Integer status;
    
    private LocalDateTime lastLoginTime;
    
    private String lastLoginIp;
    
    @TableLogic
    private Integer deleted;
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
