package com.example.opsaiagent.snapshot.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.diagnosis.service.DiagnosisScope;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.store.MetricSampleStore;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import com.example.opsaiagent.ops.service.OpsServiceManager;
import com.example.opsaiagent.snapshot.dto.SnapshotData;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.mapper.FailureSnapshotMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障快照服务测试（T12/T13）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("故障快照服务")
class FailureSnapshotServiceTest {

    @Mock
    private FailureSnapshotMapper snapshotMapper;
    @Mock
    private ServiceRegistry serviceRegistry;
    @Mock
    private OpsServiceManager opsServiceManager;
    @Mock
    private MetricSampleStore metricSampleStore;
    @Mock
    private AuditLogMapper auditLogMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Spy
    private DiagnosisScope diagnosisScope = new DiagnosisScope(null, null);

    @InjectMocks
    private FailureSnapshotService snapshotService;

    private AlertEventEntity event() {
        AlertEventEntity e = new AlertEventEntity();
        e.setId(100L);
        e.setRuleId(1L);
        e.setRuleName("cpu-high");
        e.setService("svc-a");
        e.setMetric("cpu");
        e.setOperator("gt");
        e.setThreshold(80.0);
        e.setCurrentValue(95.0);
        e.setStatus("FIRING");
        e.setDetail("cpu 超阈值");
        e.setFiredAt(LocalDateTime.now());
        return e;
    }

    private ServiceInfo serviceInfo() {
        ServiceInfo info = new ServiceInfo();
        info.setName("svc-a");
        info.setBaseUrl("http://svc-a:8081");
        info.setHealthPath("/actuator/health");
        info.setMetricsPath("/actuator/metrics");
        info.setOwner("ops");
        info.setEnv("prod");
        return info;
    }

    private OpsServiceEntity opsService() {
        OpsServiceEntity s = new OpsServiceEntity();
        s.setName("svc-a");
        s.setStatus("DOWN");
        s.setLastCheckedAt(LocalDateTime.now());
        return s;
    }

    // ==================== build ====================

    @Test
    @DisplayName("build：聚合服务信息/状态/指标窗口/审计")
    void build_aggregates() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(serviceInfo());
        when(opsServiceManager.findByName("svc-a")).thenReturn(Optional.of(opsService()));
        when(metricSampleStore.query(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(new MetricPoint(1L, 90.0), new MetricPoint(2L, 95.0)));
        AuditLogEntity log = new AuditLogEntity();
        log.setId(1L);
        log.setOperation("CREATE_SERVICE");
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log));

        SnapshotData data = snapshotService.build(event());

        assertThat(data.getEvent().getRuleName()).isEqualTo("cpu-high");
        assertThat(data.getServiceInfo().getBaseUrl()).isEqualTo("http://svc-a:8081");
        assertThat(data.getServiceStatus().getStatus()).isEqualTo("DOWN");
        assertThat(data.getMetricWindow()).hasSize(2);
        assertThat(data.getAuditLogs()).hasSize(1);
        assertThat(data.getCapturedAt()).isNotNull();
    }

    @Test
    @DisplayName("build：服务不存在时 serviceInfo/serviceStatus 为 null，不抛异常")
    void build_serviceMissing() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(null);
        when(opsServiceManager.findByName("svc-a")).thenReturn(Optional.empty());
        when(metricSampleStore.query(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        SnapshotData data = snapshotService.build(event());

        assertThat(data.getServiceInfo()).isNull();
        assertThat(data.getServiceStatus()).isNull();
        assertThat(data.getMetricWindow()).isEmpty();
    }

    // ==================== buildAndSave ====================

    @Test
    @DisplayName("buildAndSave：已存在同事件快照则跳过（幂等）")
    void buildAndSave_existing() {
        FailureSnapshotEntity existing = new FailureSnapshotEntity();
        existing.setId(1L);
        existing.setAlertEventId(100L);
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        FailureSnapshotEntity result = snapshotService.buildAndSave(event());

        assertThat(result).isSameAs(existing);
        verify(snapshotMapper, never()).insert(any(FailureSnapshotEntity.class));
    }

    @Test
    @DisplayName("buildAndSave：无快照时构建、序列化并落库")
    void buildAndSave_new() throws Exception {
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(serviceRegistry.getByName("svc-a")).thenReturn(serviceInfo());
        when(opsServiceManager.findByName("svc-a")).thenReturn(Optional.of(opsService()));
        when(metricSampleStore.query(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        FailureSnapshotEntity result = snapshotService.buildAndSave(event());

        assertThat(result.getAlertEventId()).isEqualTo(100L);
        assertThat(result.getRuleName()).isEqualTo("cpu-high");
        assertThat(result.getService()).isEqualTo("svc-a");
        assertThat(result.getStatus()).isEqualTo("FIRING");
        assertThat(result.getSnapshotJson()).contains("\"ruleName\"");
        assertThat(result.getSnapshotJson()).contains("cpu-high");
        verify(snapshotMapper).insert(result);
    }

    // ==================== getById / list ====================

    @Test
    @DisplayName("getById：存在返回实体")
    void getById_found() {
        FailureSnapshotEntity entity = new FailureSnapshotEntity();
        entity.setId(1L);
        when(snapshotMapper.selectById(1L)).thenReturn(entity);
        assertThat(snapshotService.getById(1L)).isSameAs(entity);
    }

    @Test
    @DisplayName("getById：不存在抛业务异常")
    void getById_notFound() {
        when(snapshotMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> snapshotService.getById(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SNAPSHOT_NOT_FOUND);
    }

    @Test
    @DisplayName("list：无条件返回（默认 limit 钳制）")
    void list_all() {
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(new FailureSnapshotEntity()));
        assertThat(snapshotService.list(null, null, 50)).hasSize(1);
    }

    @Test
    @DisplayName("list：按服务 / 告警事件过滤")
    void list_filtered() {
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(snapshotService.list("svc-a", 100L, 20)).isEmpty();
        verify(snapshotMapper).selectList(any(Wrapper.class));
    }
}
