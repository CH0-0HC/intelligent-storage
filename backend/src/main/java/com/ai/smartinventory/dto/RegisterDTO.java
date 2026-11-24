package com.ai.smartinventory.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名长度不能超过32位")
    private String username;

    // 【测试报告#1】原先只有 @NotBlank：密码 "123" 能直接注册成功。
    // 与 /user/password 修改密码的「≥6位」口径对齐，注册侧必须同样收口，
    // 否则同一个系统里「改密码要6位、注册不要」自相矛盾。
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度不能少于6位")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    // 【测试报告#1】原先无任何格式约束：手机号 "abc" 能入库并在管理员用户列表里可见。
    // 留空/不传仍放行（历史调用方、第三方接入可能不带手机号），但只要传了就必须是合法大陆手机号。
    // @Pattern 对 null 直接放行，对 "" 走 ^$ 分支，对 "abc" 报错。
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Size(max = 32, message = "昵称长度不能超过32位")
    private String nickname;

    private String role = "user";
}