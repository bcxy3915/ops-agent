package com.example.opsaiagent.ratelimit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.rate-limit")
public class RateLimitProperties {

    /**
     * 限流桶缓存：多久不访问后自动淘汰（分钟）
     */
    private long bucketExpireMinutes = 10;

    /**
     * 限流桶缓存：最多缓存多少个用户/IP
     */
    private long bucketMaxSize = 10000;
}