package com.example.opsaiagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 第三方调用容错支持（编程式 Resilience4j）。
 * <p>
 * 提供按调用名隔离的熔断器 + 重试组合执行：
 * <ul>
 *   <li>重试：网络抖动 / 瞬时 5xx 时自动重试</li>
 *   <li>熔断：持续失败达到阈值后快速失败，避免雪崩</li>
 * </ul>
 * 失败时异常向上抛出，由调用方降级。
 */
public final class ResilienceSupport {

    private static final Map<String, CircuitBreaker> CIRCUIT_BREAKERS = new ConcurrentHashMap<>();
    private static final Map<String, Retry> RETRIES = new ConcurrentHashMap<>();
    private static volatile ResilienceProperties properties = new ResilienceProperties();

    private ResilienceSupport() {
    }

    /**
     * 更新全局容错配置（启动时由组件调用；测试中可注入自定义配置）
     */
    public static void configure(ResilienceProperties props) {
        if (props != null) {
            properties = props;
        }
    }

    /**
     * 在熔断 + 重试保护下执行第三方调用。
     *
     * @param name     调用名（隔离熔断状态），如 "rerank"
     * @param supplier 实际调用
     * @return 调用结果
     * @throws RuntimeException 重试耗尽或熔断打开时抛出
     */
    public static <T> T execute(String name, Supplier<T> supplier) {
        CircuitBreaker cb = circuitBreaker(name);
        Retry retry = retry(name);
        return cb.executeSupplier(() -> retry.executeSupplier(supplier));
    }

    /**
     * 重置全部熔断/重试状态（测试隔离用）
     */
    public static void reset() {
        CIRCUIT_BREAKERS.clear();
        RETRIES.clear();
        properties = new ResilienceProperties();
    }

    private static CircuitBreaker circuitBreaker(String name) {
        return CIRCUIT_BREAKERS.computeIfAbsent(name, n -> {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .failureRateThreshold(properties.getCbFailureRateThreshold())
                    .slidingWindowSize(properties.getCbSlidingWindowSize())
                    .minimumNumberOfCalls(Math.max(2, properties.getCbSlidingWindowSize() / 2))
                    .waitDurationInOpenState(Duration.ofSeconds(properties.getCbWaitDurationSec()))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .build();
            return CircuitBreaker.of(name, config);
        });
    }

    private static Retry retry(String name) {
        return RETRIES.computeIfAbsent(name, n -> {
            RetryConfig config = RetryConfig.custom()
                    .maxAttempts(properties.getRetryMaxAttempts())
                    .waitDuration(Duration.ofMillis(properties.getRetryWaitMs()))
                    .build();
            return Retry.of(name, config);
        });
    }
}
