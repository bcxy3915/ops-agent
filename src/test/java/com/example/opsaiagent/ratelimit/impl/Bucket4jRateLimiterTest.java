package com.example.opsaiagent.ratelimit.impl;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bucket4jRateLimiter 单元测试
 * 覆盖：令牌桶初始化 / 限流放行与拦截 / 不同 key 独立桶
 */
@DisplayName("Bucket4j 限流器")
class Bucket4jRateLimiterTest {

    private Bucket4jRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new Bucket4jRateLimiter();
        ReflectionTestUtils.setField(limiter, "bucketExpireMinutes", 10L);
        ReflectionTestUtils.setField(limiter, "bucketMaxSize", 100L);
        limiter.init();
    }

    @Test
    @DisplayName("init：初始化缓存实例")
    void init_createsCache() {
        Cache<String, Bucket> cache = limiter.getBucketCache();
        assertThat(cache).isNotNull();
    }

    @Test
    @DisplayName("tryAcquire：限额内放行，超出拦截")
    void tryAcquire_allowThenBlock() {
        assertThat(limiter.tryAcquire("login:ip:1.2.3.4", 1, 60)).isTrue();
        assertThat(limiter.tryAcquire("login:ip:1.2.3.4", 1, 60)).isFalse();
    }

    @Test
    @DisplayName("tryAcquire：多个 key 互不影响")
    void tryAcquire_independentKeys() {
        assertThat(limiter.tryAcquire("a", 1, 60)).isTrue();
        assertThat(limiter.tryAcquire("b", 1, 60)).isTrue();
        assertThat(limiter.tryAcquire("b", 1, 60)).isFalse();
        // a 的桶仍可消费
        assertThat(limiter.tryAcquire("a", 1, 60)).isFalse();
    }

    @Test
    @DisplayName("tryAcquire：限额大于 1 时按次数放行")
    void tryAcquire_limitThree() {
        assertThat(limiter.tryAcquire("k", 3, 60)).isTrue();
        assertThat(limiter.tryAcquire("k", 3, 60)).isTrue();
        assertThat(limiter.tryAcquire("k", 3, 60)).isTrue();
        assertThat(limiter.tryAcquire("k", 3, 60)).isFalse();
    }
}
