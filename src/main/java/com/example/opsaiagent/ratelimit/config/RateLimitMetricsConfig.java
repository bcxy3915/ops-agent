package com.example.opsaiagent.ratelimit.config;

import com.example.opsaiagent.ratelimit.impl.Bucket4jRateLimiter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

/**
 * 把限流桶缓存的命中率 / 大小 / 淘汰数注册到 Micrometer
 * 之后可以从 /actuator/metrics/ops.ratelimit.bucket.* 看到实时数据
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnBean(Bucket4jRateLimiter.class)
public class RateLimitMetricsConfig {

    private final Bucket4jRateLimiter rateLimiter;
    private final MeterRegistry registry;

    @PostConstruct
    public void register() {
        var cache = rateLimiter.getBucketCache();
        if (cache == null) return;

        registry.gauge("ops.ratelimit.bucket.size", cache, Cache::estimatedSize);

        registry.gauge("ops.ratelimit.bucket.hitRate",
                cache, c -> {
                    CacheStats s = c.stats();
                    long req = s.requestCount();
                    return req == 0 ? 0d : (double) s.hitCount() / req;
                });

        registry.gauge("ops.ratelimit.bucket.evictions",
                cache, c -> c.stats().evictionCount());
    }
}