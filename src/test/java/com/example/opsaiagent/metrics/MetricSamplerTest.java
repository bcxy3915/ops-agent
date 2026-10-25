package com.example.opsaiagent.metrics;

import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.dto.MetricSnapshotResponse;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MetricSampler 单元测试
 * 覆盖：空服务列表 / 单服务采样快照 / 时序点存储 / 历史查询过滤 / 落库 / 落库失败容错
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指标采样器")
class MetricSamplerTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private ActuatorMetricFetcher fetcher;

    @Mock
    private MetricSampleStore metricSampleStore;

    @InjectMocks
    private MetricSampler sampler;

    private ServiceInfo svc(String name) {
        ServiceInfo info = new ServiceInfo();
        info.setName(name);
        info.setTenantId("t-default");
        info.setBaseUrl("http://" + name + ":8080");
        info.setHealthPath("/actuator/health");
        return info;
    }

    @Test
    @DisplayName("sample：无注册服务时直接返回")
    void sample_emptyServices() {
        when(serviceRegistry.listAll()).thenReturn(List.of());

        sampler.sample();

        verifyNoInteractions(fetcher);
        verifyNoInteractions(metricSampleStore);
    }

    @Test
    @DisplayName("sample：采样成功生成快照与时序点，并落库 5 个指标")
    void sample_success() {
        ServiceInfo service = svc("svc-a");
        when(serviceRegistry.listAll()).thenReturn(List.of(service));

        // 按指标名返回不同值
        when(fetcher.fetch(anyString(), anyString())).thenAnswer(inv -> {
            String metric = inv.getArgument(1);
            return switch (metric) {
                case "process.cpu.usage" -> new ActuatorMetricFetcher.MetricValue(0.5, 0, 0, 0);
                case "jvm.memory.used" -> new ActuatorMetricFetcher.MetricValue(100L * 1024 * 1024, 0, 0, 0);
                case "jvm.memory.max" -> new ActuatorMetricFetcher.MetricValue(1024L * 1024 * 1024, 0, 0, 0);
                case "jvm.threads.live" -> new ActuatorMetricFetcher.MetricValue(10, 0, 0, 0);
                case "jvm.gc.pause" -> new ActuatorMetricFetcher.MetricValue(0, 2, 10, 5);
                case "http.server.requests" -> new ActuatorMetricFetcher.MetricValue(0, 100, 0, 0);
                case "hikaricp.connections.active" -> new ActuatorMetricFetcher.MetricValue(3, 0, 0, 0);
                case "hikaricp.connections.pending" -> new ActuatorMetricFetcher.MetricValue(1, 0, 0, 0);
                default -> new ActuatorMetricFetcher.MetricValue(0, 0, 0, 0);
            };
        });

        sampler.sample();

        MetricSnapshotResponse.Snapshot snapshot = sampler.getLatestSnapshot("svc-a");
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getCpu()).isEqualTo(0.5);
        assertThat(snapshot.getMemoryUsed()).isEqualTo(100L * 1024 * 1024);
        assertThat(snapshot.getThreadCount()).isEqualTo(10);
        // gcPause = totalTime / count * 1000 = 10 / 2 * 1000
        assertThat(snapshot.getGcPause()).isEqualTo(5000);
        // 首次采样无上次记录，httpCount 为 0
        assertThat(snapshot.getHttpCount()).isZero();
        assertThat(snapshot.getHikariActive()).isEqualTo(3);
        assertThat(snapshot.getHikariPending()).isEqualTo(1);

        // 落库 5 个指标
        verify(metricSampleStore).saveBatch(eq("t-default"), eq("svc-a"), anyLong(), anyMap());
    }

    @Test
    @DisplayName("sample：指标缺失时使用默认值")
    void sample_missingMetrics_usesDefaults() {
        ServiceInfo service = svc("svc-a");
        when(serviceRegistry.listAll()).thenReturn(List.of(service));
        when(fetcher.fetch(anyString(), anyString())).thenReturn(null);

        sampler.sample();

        MetricSnapshotResponse.Snapshot snapshot = sampler.getLatestSnapshot("svc-a");
        assertThat(snapshot.getCpu()).isZero();
        assertThat(snapshot.getMemoryUsed()).isZero();
        // memMax 默认 1GB
        assertThat(snapshot.getMemoryMax()).isEqualTo(1024L * 1024 * 1024);
    }

    @Test
    @DisplayName("sample：落库失败不影响快照生成")
    void sample_persistFailureDoesNotBreakSampling() {
        ServiceInfo service = svc("svc-a");
        when(serviceRegistry.listAll()).thenReturn(List.of(service));
        when(fetcher.fetch(anyString(), anyString()))
                .thenReturn(new ActuatorMetricFetcher.MetricValue(0.5, 0, 0, 0));
        doThrow(new RuntimeException("db down")).when(metricSampleStore)
                .saveBatch(anyString(), anyString(), anyLong(), anyMap());

        sampler.sample();

        MetricSnapshotResponse.Snapshot snapshot = sampler.getLatestSnapshot("svc-a");
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getCpu()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("getHistory：按时间范围过滤")
    void getHistory_filtersByRange() {
        ServiceInfo service = svc("svc-a");
        when(serviceRegistry.listAll()).thenReturn(List.of(service));
        when(fetcher.fetch(anyString(), anyString()))
                .thenReturn(new ActuatorMetricFetcher.MetricValue(0.5, 0, 0, 0));

        long now = System.currentTimeMillis();
        sampler.sample();

        List<MetricPoint> inRange = sampler.getHistory("svc-a", "cpu", now - 1, now + 1);
        assertThat(inRange).hasSize(1);
        assertThat(inRange.get(0).getValue()).isEqualTo(0.5);

        List<MetricPoint> outRange = sampler.getHistory("svc-a", "cpu", now + 10, now + 100);
        assertThat(outRange).isEmpty();
    }

    @Test
    @DisplayName("getHistory：未知服务/未知指标返回空")
    void getHistory_unknownKeys() {
        assertThat(sampler.getHistory("no-such", "cpu", 0, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("getLatestSnapshot：未知服务返回 null")
    void getLatestSnapshot_unknown() {
        assertThat(sampler.getLatestSnapshot("no-such")).isNull();
    }
}
