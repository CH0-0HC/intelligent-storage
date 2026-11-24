package com.ai.smartinventory.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 请求频率限制注解
 * 基于Redis滑动窗口实现，支持分钟级和小时级限流
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流key前缀，默认使用类名+方法名
     */
    String key() default "";

    /**
     * 每分钟允许的请求次数，默认60
     */
    int permitsPerMinute() default 60;

    /**
     * 每小时允许的请求次数，默认1000
     */
    int permitsPerHour() default 1000;
}