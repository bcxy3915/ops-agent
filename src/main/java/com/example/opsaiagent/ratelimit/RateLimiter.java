package com.example.opsaiagent.ratelimit;

/**
 * 限流器抽象接口
 * 设计要点：接口只暴露"能力"，不暴露实现细节。
 * 未来可替换为：
 * - Bucket4jRateLimiter（单机，当前）
 * - RedisRateLimiter（分布式）
 * - SentinelRateLimiter（企业级）
 */
public interface RateLimiter {

    /**
     * 尝试获取一个令牌
     * @param key           限流键（如 "login:ip:127.0.0.1"）
     * @param limit         周期内允许的最大请求数
     * @param periodSeconds 周期（秒）
     * @return true=允许通过，false=触发限流
     */
    boolean tryAcquire(String key, int limit, long periodSeconds);
}