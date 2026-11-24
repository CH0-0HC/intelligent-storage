package com.ai.smartinventory.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import com.ai.smartinventory.security.JdbcSaTokenDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把 Sa-Token 的会话存储从「进程内存」换成「MySQL」。
 *
 * 【不这么做的后果（实测复现过）】
 * 只依赖 sa-token-spring-boot3-starter 时，Sa-Token 用内存 DAO：
 *   重启前 GET /api/ai/dashboard（带 token）→ 200
 *   重启后同一个 token 再请求             → 401「token 无效」
 * 而浏览器 localStorage 里仍留着旧 token，前端拦截器一收到 401
 * 就清 token 并跳登录页 —— 用户看到的就是「用着用着突然被强制登出」。
 * 这对开发期尤其致命：每改一次后端重启，所有人的登录态就全废一次。
 *
 * 【为什么不用 Redis】
 * 官方方案是引入 sa-token-redis-jackson，但本机 Redis（6379）并未运行，
 * 而且 @RateLimit 已经容忍 Redis 不可用；一旦把会话也压到 Redis，
 * 反而会让「Redis 没起 → 谁都登录不了」。项目本来就强依赖 MySQL，
 * 所以直接落库是这里代价最小、最稳的选择。
 *
 * 注意：这里显式调用 SaManager.setSaTokenDao(...)，不依赖 starter 的装配顺序，
 * 确保在第一个请求到来之前就已生效。
 */
@Slf4j
@Configuration
public class SaTokenDaoConfig {

    @Bean
    public SaTokenDao saTokenDao(JdbcTemplate jdbcTemplate) {
        JdbcSaTokenDao dao = new JdbcSaTokenDao(jdbcTemplate);
        SaManager.setSaTokenDao(dao);
        log.info("Sa-Token 会话存储已切换为 MySQL（表 sys_token），后端重启不再丢失登录态");
        return dao;
    }
}
