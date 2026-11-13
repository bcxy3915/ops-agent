package com.example.opsaiagent.ratelimit;

/**
 * 限流器抽象接口
 * 设计要点：接口只暴露"能力"，不暴露实现细节。
 * 支持配置选择 Bucket4jRateLimiter（单机）或 RedisRateLimiter（分布式）。
 */
public interface RateLimiter {

    /**
     * 尝试获取一个令牌
     * @param key           限流键（如 "login:ip:127.0.0.1"）
     * @param limit         令牌桶容量
     * @param periodSeconds 空桶连续补充到满桶所需秒数
     * @return true=允许通过，false=触发限流
     */
    boolean tryAcquire(String key, int limit, long periodSeconds);
}
