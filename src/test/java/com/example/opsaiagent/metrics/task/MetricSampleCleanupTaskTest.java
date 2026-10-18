package com.example.opsaiagent.metrics.task;

import com.example.opsaiagent.metrics.store.MetricSampleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * MetricSampleCleanupTask 单元测试
 * 覆盖：按保留天数删除过期数据 / 清理失败不中断
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指标采样清理任务")
class MetricSampleCleanupTaskTest {

    @Mock
    private MetricSampleStore metricSampleStore;

    private MetricSampleCleanupTask task;

    @BeforeEach
    void setUp() {
        task = new MetricSampleCleanupTask(metricSampleStore);
        ReflectionTestUtils.setField(task, "retentionDays", 7);
    }

    @Test
    @DisplayName("cleanup：删除保留期外的采样数据")
    void cleanup_deletesExpired() {
        long beforeCall = System.currentTimeMillis() - 7L * 24 * 3600_000L;

        task.cleanup();

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(metricSampleStore).deleteOlderThan(captor.capture());
        // beforeMs 应约为 now - 7 天（允许小幅偏差）
        assertThat(captor.getValue()).isBetween(beforeCall - 60_000, beforeCall + 60_000);
    }

    @Test
    @DisplayName("cleanup：存储异常时静默记录不抛出")
    void cleanup_handlesStoreFailure() {
        doThrow(new RuntimeException("db down")).when(metricSampleStore).deleteOlderThan(org.mockito.ArgumentMatchers.anyLong());

        assertThatCode(task::cleanup).doesNotThrowAnyException();
    }
}
