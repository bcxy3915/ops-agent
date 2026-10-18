package com.example.opsaiagent.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 第三方调用容错配置（超时 / 重试 / 熔断）
 * 前缀：ops-agent.resilience
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.resilience")
public class ResilienceProperties {

    /** HTTP 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** HTTP 读取超时（毫秒） */
    private int readTimeoutMs = 15000;

    /** 重试最大次数（含首次请求） */
    private int retryMaxAttempts = 2;

    /** 重试间隔（毫秒） */
    private long retryWaitMs = 1000;

    /** 熔断失败率阈值（百分比），达到后打开熔断 */
    private float cbFailureRateThreshold = 50;

    /** 熔断滑动窗口大小（请求数） */
    private int cbSlidingWindowSize = 10;

    /** 熔断打开持续时间（秒） */
    private long cbWaitDurationSec = 30;
}
