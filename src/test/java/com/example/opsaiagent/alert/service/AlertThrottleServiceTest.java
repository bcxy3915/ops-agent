package com.example.opsaiagent.alert.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertThrottleService 单元测试
 * 覆盖：静默期检查 / 静默期过期 / 每小时事件上限 / 小时窗口滚动 / reset
 */
@DisplayName("告警静默与节流")
class AlertThrottleServiceTest {

    private AlertThrottleService throttle;
    private final long now = 1_700_000_000_000L;

    @BeforeEach
    void setUp() {
        throttle = new AlertThrottleService();
    }

    @Test
    @DisplayName("shouldSilence：未记录过触发返回 false")
    void shouldSilence_noRecord() {
        assertThat(throttle.shouldSilence(1L, 30, now)).isFalse();
    }

    @Test
    @DisplayName("shouldSilence：静默期内返回 true")
    void shouldSilence_withinWindow() {
        throttle.recordFired(1L, now - 10_000);

        assertThat(throttle.shouldSilence(1L, 30, now)).isTrue();
    }

    @Test
    @DisplayName("shouldSilence：超过静默期返回 false")
    void shouldSilence_afterWindow() {
        throttle.recordFired(1L, now - 40_000);

        assertThat(throttle.shouldSilence(1L, 30, now)).isFalse();
    }

    @Test
    @DisplayName("shouldSilence：静默时长为 0 不静默")
    void shouldSilence_zeroDuration() {
        throttle.recordFired(1L, now - 10_000);

        assertThat(throttle.shouldSilence(1L, 0, now)).isFalse();
    }

    @Test
    @DisplayName("allowEvent：未达上限允许")
    void allowEvent_underLimit() {
        assertThat(throttle.allowEvent("t1", "svc-a", now)).isTrue();
    }

    @Test
    @DisplayName("allowEvent：达到每小时上限拒绝")
    void allowEvent_overLimit() {
        for (int i = 0; i < AlertThrottleService.HOURLY_EVENT_LIMIT; i++) {
            throttle.recordEvent("t1", "svc-a", now);
        }

        assertThat(throttle.allowEvent("t1", "svc-a", now)).isFalse();
    }

    @Test
    @DisplayName("allowEvent：跨小时窗口后计数重置")
    void allowEvent_rollsWindow() {
        for (int i = 0; i < AlertThrottleService.HOURLY_EVENT_LIMIT; i++) {
            throttle.recordEvent("t1", "svc-a", now);
        }

        // 一小时后
        assertThat(throttle.allowEvent("t1", "svc-a", now + 3_600_000L + 1)).isTrue();
    }

    @Test
    @DisplayName("recordFired / recordEvent：不同服务相互独立")
    void counters_isolatedByService() {
        for (int i = 0; i < AlertThrottleService.HOURLY_EVENT_LIMIT; i++) {
            throttle.recordEvent("t1", "svc-a", now);
        }

        assertThat(throttle.allowEvent("t1", "svc-a", now)).isFalse();
        assertThat(throttle.allowEvent("t1", "svc-b", now)).isTrue();
    }

    @Test
    @DisplayName("recordEvent：不同租户的同名服务相互独立")
    void counters_isolatedByTenant() {
        for (int i = 0; i < AlertThrottleService.HOURLY_EVENT_LIMIT; i++) {
            throttle.recordEvent("t1", "svc-a", now);
        }

        assertThat(throttle.allowEvent("t1", "svc-a", now)).isFalse();
        assertThat(throttle.allowEvent("t2", "svc-a", now)).isTrue();
    }

    @Test
    @DisplayName("reset：清空静默与节流状态")
    void reset_clears() {
        throttle.recordFired(1L, now - 10_000);
        for (int i = 0; i < AlertThrottleService.HOURLY_EVENT_LIMIT; i++) {
            throttle.recordEvent("t1", "svc-a", now);
        }

        throttle.reset();

        assertThat(throttle.shouldSilence(1L, 30, now)).isFalse();
        assertThat(throttle.allowEvent("t1", "svc-a", now)).isTrue();
    }
}
