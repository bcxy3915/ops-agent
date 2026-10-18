package com.example.opsaiagent.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.metrics.dto.MetricHistoryResponse;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.store.MetricSampleStore;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetricSampleController 单元测试
 * 覆盖：默认 5 指标查询 / 单指标查询 / 服务不存在 / 时间范围解析
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指标采样历史接口")
class MetricSampleControllerTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private MetricSampleStore metricSampleStore;

    @InjectMocks
    private MetricSampleController controller;

    private ServiceInfo svc(String name) {
        ServiceInfo info = new ServiceInfo();
        info.setName(name);
        return info;
    }

    @Test
    @DisplayName("query：默认返回 5 个指标的时序")
    void query_allMetrics() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(svc("svc-a"));
        when(metricSampleStore.query(eq("svc-a"), eq("cpu"), anyLong(), anyLong()))
                .thenReturn(List.of(new MetricPoint(1000, 0.5)));

        ApiResponse<MetricHistoryResponse> result = controller.query("svc-a", null, "1h");

        assertThat(result.getData().getService()).isEqualTo("svc-a");
        assertThat(result.getData().getRange()).isEqualTo("1h");
        assertThat(result.getData().getSeries()).containsKeys("cpu", "memory", "threadCount", "gcPause", "hikariActive");
        assertThat(result.getData().getSeries().get("cpu")).hasSize(1);
    }

    @Test
    @DisplayName("query：指定单指标只返回该指标")
    void query_singleMetric() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(svc("svc-a"));
        when(metricSampleStore.query(eq("svc-a"), eq("cpu"), anyLong(), anyLong()))
                .thenReturn(List.of(new MetricPoint(1000, 0.5)));

        ApiResponse<MetricHistoryResponse> result = controller.query("svc-a", "cpu", "1h");

        assertThat(result.getData().getSeries()).containsOnlyKeys("cpu");
    }

    @Test
    @DisplayName("query：服务不存在抛 BusinessException")
    void query_serviceNotFound() {
        when(serviceRegistry.getByName("unknown")).thenReturn(null);

        assertThatThrownBy(() -> controller.query("unknown", null, "1h"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("query：range 5m 映射到最近 5 分钟")
    void query_rangeParsing() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(svc("svc-a"));

        controller.query("svc-a", "cpu", "5m");

        long now = System.currentTimeMillis();
        verify(metricSampleStore).query(eq("svc-a"), eq("cpu"),
                longThat(start -> start >= now - 5 * 60 * 1000L - 10_000 && start <= now - 5 * 60 * 1000L + 10_000),
                longThat(end -> end >= now - 10_000 && end <= now + 10_000));
    }

    @Test
    @DisplayName("query：未知 range 回退为 1 小时")
    void query_unknownRangeFallsBack() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(svc("svc-a"));

        ApiResponse<MetricHistoryResponse> result = controller.query("svc-a", "cpu", "weird");

        assertThat(result.getData().getRange()).isEqualTo("1h");
    }
}
