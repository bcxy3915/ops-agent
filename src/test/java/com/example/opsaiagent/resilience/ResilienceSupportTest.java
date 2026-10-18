package com.example.opsaiagent.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ResilienceSupport 单元测试
 * 覆盖：成功执行 / 重试后成功 / 重试耗尽抛异常 / 熔断打开快速失败 / reset 清空状态
 */
@DisplayName("第三方调用容错")
class ResilienceSupportTest {

    @AfterEach
    void tearDown() {
        ResilienceSupport.reset();
    }

    @Test
    @DisplayName("execute：正常调用直接返回")
    void execute_success() {
        String result = ResilienceSupport.execute("test-ok", () -> "hello");

        assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("execute：前两次失败后重试成功")
    void execute_retriesUntilSuccess() {
        ResilienceProperties props = new ResilienceProperties();
        props.setRetryMaxAttempts(3);
        props.setRetryWaitMs(1);
        ResilienceSupport.configure(props);

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> flaky = () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("temp failure");
            }
            return "recovered";
        };

        String result = ResilienceSupport.execute("test-retry", flaky);

        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("execute：持续失败时重试耗尽并抛出")
    void execute_throwsAfterRetriesExhausted() {
        ResilienceProperties props = new ResilienceProperties();
        props.setRetryMaxAttempts(3);
        props.setRetryWaitMs(1);
        ResilienceSupport.configure(props);

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> failing = () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> ResilienceSupport.execute("test-fail", failing))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("execute：失败率达到阈值后熔断打开，快速失败不再调用")
    void execute_opensCircuitAfterFailures() {
        ResilienceProperties props = new ResilienceProperties();
        props.setRetryMaxAttempts(1);
        props.setCbSlidingWindowSize(4);
        props.setCbFailureRateThreshold(50);
        props.setCbWaitDurationSec(60);
        ResilienceSupport.configure(props);

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> failing = () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        };

        // 填满滑动窗口（4 次全失败，失败率 100% > 50%）
        for (int i = 0; i < 4; i++) {
            try {
                ResilienceSupport.execute("test-cb", failing);
            } catch (Exception ignored) {
                // expected
            }
        }

        int before = calls.get();
        // 熔断已打开：直接快速失败，不再调用 supplier
        assertThatThrownBy(() -> ResilienceSupport.execute("test-cb", failing))
                .isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("execute：不同调用名相互隔离熔断状态")
    void execute_isolatesByCallName() {
        ResilienceProperties props = new ResilienceProperties();
        props.setRetryMaxAttempts(1);
        props.setCbSlidingWindowSize(4);
        props.setCbFailureRateThreshold(50);
        props.setCbWaitDurationSec(60);
        ResilienceSupport.configure(props);

        Supplier<String> failing = () -> {
            throw new IllegalStateException("boom");
        };
        for (int i = 0; i < 4; i++) {
            try {
                ResilienceSupport.execute("test-cb-a", failing);
            } catch (Exception ignored) {
            }
        }

        // 调用名 A 已熔断，调用名 B 仍正常
        assertThatThrownBy(() -> ResilienceSupport.execute("test-cb-a", failing))
                .isInstanceOf(RuntimeException.class);
        assertThat(ResilienceSupport.execute("test-cb-b", () -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("reset：清空熔断状态后恢复执行")
    void reset_clearsState() {
        ResilienceProperties props = new ResilienceProperties();
        props.setRetryMaxAttempts(1);
        props.setCbSlidingWindowSize(4);
        props.setCbFailureRateThreshold(50);
        props.setCbWaitDurationSec(60);
        ResilienceSupport.configure(props);

        Supplier<String> failing = () -> {
            throw new IllegalStateException("boom");
        };
        for (int i = 0; i < 4; i++) {
            try {
                ResilienceSupport.execute("test-reset", failing);
            } catch (Exception ignored) {
            }
        }

        ResilienceSupport.reset();

        assertThat(ResilienceSupport.execute("test-reset", () -> "again")).isEqualTo("again");
    }
}
