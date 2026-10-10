package com.example.opsaiagent.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.metrics.MetricSampler;
import com.example.opsaiagent.metrics.dto.MetricHistoryResponse;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.dto.MetricSnapshotResponse;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MetricController 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指标监控接口")
class MetricControllerTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private MetricSampler metricSampler;

    @InjectMocks
    private MetricController controller;

    private ServiceInfo svc(String name) {
        ServiceInfo info = new ServiceInfo();
        info.setName(name);
        return info;
    }

    // ==================== snapshot ====================

    @Test
    @DisplayName("指标快照：有采样数据")
    void snapshot_withData() {
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));
        MetricSnapshotResponse.Snapshot snapshot = MetricSnapshotResponse.Snapshot.builder().build();
        when(metricSampler.getLatestSnapshot("todo-service")).thenReturn(snapshot);

        ApiResponse<MetricSnapshotResponse> result = controller.snapshot("todo-service");

        assertThat(result.getData().getService()).isEqualTo("todo-service");
        assertThat(result.getData().getSnapshot()).isSameAs(snapshot);
    }

    @Test
    @DisplayName("指标快照：无采样数据返回空对象")
    void snapshot_noData() {
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));
        when(metricSampler.getLatestSnapshot("todo-service")).thenReturn(null);

        ApiResponse<MetricSnapshotResponse> result = controller.snapshot("todo-service");

        assertThat(result.getData().getSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("指标快照：服务不存在抛 BusinessException")
    void snapshot_serviceNotFound() {
        when(serviceRegistry.getByName("unknown")).thenReturn(null);

        assertThatThrownBy(() -> controller.snapshot("unknown"))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== history ====================

    @Test
    @DisplayName("指标历史：默认 5m")
    void history_default() {
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));
        when(metricSampler.getHistory(eq("todo-service"), anyString(), anyLong(), anyLong()))
                .thenReturn(List.<MetricPoint>of());

        ApiResponse<MetricHistoryResponse> result = controller.history("todo-service", "5m");

        assertThat(result.getData().getService()).isEqualTo("todo-service");
        assertThat(result.getData().getRange()).isEqualTo("5m");
        assertThat(result.getData().getSeries()).containsKeys(
                "cpu", "memory", "threadCount", "gcPause", "hikariActive");
    }

    @Test
    @DisplayName("指标历史：6h")
    void history_6h() {
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));
        when(metricSampler.getHistory(eq("todo-service"), anyString(), anyLong(), anyLong()))
                .thenReturn(List.<MetricPoint>of());

        ApiResponse<MetricHistoryResponse> result = controller.history("todo-service", "6h");

        assertThat(result.getData().getRange()).isEqualTo("6h");
    }

    @Test
    @DisplayName("指标历史：无效 range 降级为 5m")
    void history_invalidRange() {
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));
        when(metricSampler.getHistory(eq("todo-service"), anyString(), anyLong(), anyLong()))
                .thenReturn(List.<MetricPoint>of());

        ApiResponse<MetricHistoryResponse> result = controller.history("todo-service", "invalid");

        assertThat(result.getData().getRange()).isEqualTo("invalid");
    }

    @Test
    @DisplayName("指标历史：服务不存在抛 BusinessException")
    void history_serviceNotFound() {
        when(serviceRegistry.getByName("unknown")).thenReturn(null);

        assertThatThrownBy(() -> controller.history("unknown", "5m"))
                .isInstanceOf(BusinessException.class);
    }
}