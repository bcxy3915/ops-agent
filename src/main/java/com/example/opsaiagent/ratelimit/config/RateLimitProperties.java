package com.example.opsaiagent.ratelimit.config;

import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

/**
 * 限流相关配置
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "ops-agent.rate-limit")
public class RateLimitProperties {

    @Pattern(regexp = "bucket4j|redis")
    private String type = "bucket4j";

    @NotBlank
    private String redisKeyPrefix = "ops:ratelimit:v1:";

    /** 只有边缘代理会覆盖转发头且后端不允许直接访问时才启用。 */
    private boolean trustForwardedHeaders = false;

    /** 按注解 key 覆盖策略；相同 key 的接口共享同一额度。 */
    @Valid
    private Map<String, Policy> policies = new HashMap<>();

    @Data
    public static class Policy {
        @Min(1)
        @Max(1_000_000)
        private int limit = 60;

        @Min(1)
        @Max(86_400)
        private long period = 60;
    }

    /**
     * 限流桶缓存：多久不访问后自动淘汰（分钟）
     */
    private long bucketExpireMinutes = 10;

    /**
     * 限流桶缓存：最多缓存多少个用户/IP
     */
    private long bucketMaxSize = 10000;
}
