package com.example.opsaiagent.ratelimit.impl;

import com.example.opsaiagent.ratelimit.RateLimiter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Bucket4j 的单机限流实现
 * 原理：令牌桶算法
 * - 桶按固定速率补充令牌
 * - 每次请求消耗一个令牌
 * - 桶空则限流
 * 缓存策略：
 * - 每个 key 对应一个 Bucket
 * - 用 Caffeine 缓存，长时间不访问自动淘汰，避免内存泄漏
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "ops-agent.rate-limit.type",
        havingValue = "bucket4j",
        matchIfMissing = true
)
public class Bucket4jRateLimiter implements RateLimiter {

    /** 桶缓存：多久不访问后自动淘汰（分钟） */
    @Value("${ops-agent.rate-limit.bucket-expire-minutes:10}")
    private long bucketExpireMinutes;

    /** 桶缓存：最多缓存多少个 key（超出按 LRU 淘汰） */
    @Value("${ops-agent.rate-limit.bucket-max-size:10000}")
    private long bucketMaxSize;

    /** 用 Caffeine 替换 ConcurrentHashMap，自动淘汰长时间不用的桶 */
    private Cache<String, Bucket> bucketCache;

    @PostConstruct
    public void init() {
        this.bucketCache = Caffeine.newBuilder()
                // 超过该时长未访问的桶被自动清理（防内存泄漏核心）
                .expireAfterAccess(Duration.ofMinutes(bucketExpireMinutes))
                // 最多缓存多少个，超出后按 LRU 淘汰（兜底防线）
                .maximumSize(bucketMaxSize)
                // 开启命中率统计，便于后续接 Micrometer 观察
                .recordStats()
                .build();
        log.info("限流桶缓存初始化完成: expireAfterAccess={}min, maximumSize={}",
                bucketExpireMinutes, bucketMaxSize);
    }

    @Override
    public boolean tryAcquire(String key, int limit, long periodSeconds) {
        // Caffeine 的 get(key, loader) 语义与 computeIfAbsent 一致
        Bucket bucket = bucketCache.get(key, k -> createBucket(limit, periodSeconds));
        boolean allowed = bucket.tryConsume(1);
        if (!allowed) {
            log.debug("限流触发: key={}, limit={}/{}s", key, limit, periodSeconds);
        }
        return allowed;
    }

    private Bucket createBucket(int limit, long periodSeconds) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit)
                        .refillGreedy(limit, Duration.ofSeconds(periodSeconds))
                        .build())
                .build();
    }

    /**
     * 暴露缓存实例（供监控注册使用）
     */
    public Cache<String, Bucket> getBucketCache() {
        return bucketCache;
    }
}