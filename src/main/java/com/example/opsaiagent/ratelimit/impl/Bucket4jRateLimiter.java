package com.example.opsaiagent.ratelimit.impl;

import com.example.opsaiagent.ratelimit.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Bucket4j 的单机限流实现
 * 原理：令牌桶算法
 * - 桶按固定速率补充令牌
 * - 每次请求消耗一个令牌
 * - 桶空则限流
 * 缓存策略：
 * - 每个 key 对应一个 Bucket
 * - 用 ConcurrentHashMap 缓存，避免每次重建
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "ops-agent.rate-limit.type",
        havingValue = "bucket4j",
        matchIfMissing = true
)
public class Bucket4jRateLimiter implements RateLimiter {

    private final Map<String, Bucket> bucketCache = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int limit, long periodSeconds) {
        Bucket bucket = bucketCache.computeIfAbsent(key, k -> createBucket(limit, periodSeconds));
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
}