package com.example.opsaiagent.metrics.store;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.entity.MetricSampleEntity;
import com.example.opsaiagent.metrics.mapper.MetricSampleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetricSampleStore 单元测试
 * 覆盖：单点保存 / 批量保存 / 时间范围查询 / 最近值查询 / 过期删除 / 落库失败容错
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指标采样存储")
class MetricSampleStoreTest {

    @Mock
    private MetricSampleMapper mapper;

    @InjectMocks
    private MetricSampleStore store;

    private long toMs(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("save：写入单点采样")
    void save_insertsEntity() {
        long now = System.currentTimeMillis();
        store.save("svc-a", "cpu", now, 0.42);

        verify(mapper).insert(any(MetricSampleEntity.class));
    }

    @Test
    @DisplayName("save：写入租户 ID")
    void save_withTenant() {
        store.save("t1", "svc-a", "cpu", System.currentTimeMillis(), 0.42);

        ArgumentCaptor<MetricSampleEntity> captor =
                ArgumentCaptor.forClass(MetricSampleEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("save：落库失败不抛出")
    void save_swallowsInsertError() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(MetricSampleEntity.class));

        assertThatCode(() -> store.save("svc-a", "cpu", System.currentTimeMillis(), 0.42))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("saveBatch：空集合直接返回")
    void saveBatch_emptyPoints() {
        store.saveBatch("svc-a", System.currentTimeMillis(), Map.of());

        verify(mapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("saveBatch：批量保存多个指标（一次 SQL）")
    void saveBatch_insertsEachMetric() {
        long now = System.currentTimeMillis();
        Map<String, Double> points = Map.of("cpu", 0.5, "memory", 1024.0, "threadCount", 12.0);

        store.saveBatch("svc-a", now, points);

        ArgumentCaptor<List<MetricSampleEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    @DisplayName("query：按时间范围返回升序数据点")
    void query_returnsPoints() {
        LocalDateTime t1 = LocalDateTime.now().minusSeconds(60);
        LocalDateTime t2 = LocalDateTime.now();
        MetricSampleEntity e1 = entity(1L, "svc-a", "cpu", 0.3, t1);
        MetricSampleEntity e2 = entity(2L, "svc-a", "cpu", 0.4, t2);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(e1, e2));

        List<MetricPoint> points = store.query("svc-a", "cpu", toMs(t1) - 1000, toMs(t2) + 1000);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).getTimestamp()).isEqualTo(toMs(t1));
        assertThat(points.get(0).getValue()).isEqualTo(0.3);
        assertThat(points.get(1).getValue()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("query：按租户过滤")
    void query_withTenant() {
        LocalDateTime t = LocalDateTime.now();
        when(mapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(entity(1L, "svc-a", "cpu", 0.3, t)));

        List<MetricPoint> points = store.query("t1", "svc-a", "cpu",
                toMs(t) - 1000, toMs(t) + 1000);

        assertThat(points).hasSize(1);
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("queryLatest：返回最近一个采样值")
    void queryLatest_returnsLatest() {
        LocalDateTime t = LocalDateTime.now().minusSeconds(10);
        MetricSampleEntity e = entity(9L, "svc-a", "cpu", 0.7, t);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        MetricPoint point = store.queryLatest("svc-a", "cpu", toMs(t) + 1000);

        assertThat(point).isNotNull();
        assertThat(point.getValue()).isEqualTo(0.7);
        assertThat(point.getTimestamp()).isEqualTo(toMs(t));
    }

    @Test
    @DisplayName("queryLatest：无数据返回 null")
    void queryLatest_noData() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(store.queryLatest("svc-a", "cpu", System.currentTimeMillis())).isNull();
    }

    @Test
    @DisplayName("deleteOlderThan：删除过期数据")
    void deleteOlderThan_deletes() {
        when(mapper.delete(any(Wrapper.class))).thenReturn(120);

        int deleted = store.deleteOlderThan(System.currentTimeMillis() - 7 * 24 * 3600_000L);

        assertThat(deleted).isEqualTo(120);
    }

    private MetricSampleEntity entity(Long id, String service, String metric, double value, LocalDateTime t) {
        MetricSampleEntity e = new MetricSampleEntity();
        e.setId(id);
        e.setService(service);
        e.setMetric(metric);
        e.setValue(value);
        e.setSampledAt(t);
        return e;
    }
}
