package com.ai.smartinventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务总开关。
 *
 * <p>【为什么单独一个配置类而不是写在启动类上】本项目的定时任务只有一类 ——
 * 订单超时回收（见 {@code task/OrderTimeoutTask}）。把 {@code @EnableScheduling}
 * 显式落在这里，是为了让「这个应用会跑后台任务」这件事在代码结构上可见：
 * 排查「订单为什么自己变已取消」时，能从启动类的注解一眼追到任务实现，
 * 而不是靠搜 {@code @Scheduled} 全库翻。
 *
 * <p>注意：本注解只是开启调度能力，具体任务是否注册由各任务自身的
 * {@code @ConditionalOnProperty} 决定（测试环境下超时任务默认关闭，
 * 否则 H2 里没有 order 表，任务会按周期反复抛异常污染测试日志）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
