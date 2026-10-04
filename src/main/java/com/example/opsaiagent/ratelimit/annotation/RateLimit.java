package com.example.opsaiagent.ratelimit.annotation;

import java.lang.annotation.*;

/**
 * 接口限流注解
 * 用法：
 * 注解 RateLimit(key = "login", limit = 5, period = 60, dimension = RateLimit.Dimension.IP)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流标识，用于区分不同接口
     */
    String key();

    /**
     * 周期内允许的最大请求数
     */
    int limit() default 60;

    /**
     * 周期（秒）
     */
    long period() default 60;

    /**
     * 限流维度
     */
    Dimension dimension() default Dimension.IP;

    enum Dimension {
        /** 按 IP 限流（适用于未登录接口） */
        IP,
        /** 按用户限流（从 JWT 取 username） */
        USER,
        /** 全局限流（所有请求共享一个桶） */
        GLOBAL
    }
}