package com.example.opsaiagent.ratelimit.exception;

/**
 * 触发限流时抛出
 */
public class RateLimitExceededException extends RuntimeException {

    /**
     * 限流超出异常
     * @param message 提示信息
     */
    public RateLimitExceededException(String message) {
        super(message);
    }
}