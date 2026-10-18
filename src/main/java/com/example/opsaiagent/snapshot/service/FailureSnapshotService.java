package com.example.opsaiagent.snapshot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.store.MetricSampleStore;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import com.example.opsaiagent.service.OpsServiceManager;
import com.example.opsaiagent.snapshot.dto.SnapshotData;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.mapper.FailureSnapshotMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 故障快照服务（T12/T13）
 * 告警触发时聚合：告警事件 + 服务注册信息 + 服务健康状态 + 指标窗口 + 最近审计日志，
 * 序列化为结构化 JSON 落库，供查看与后续 LLM 诊断使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailureSnapshotService {

    /** 指标窗口：触发前 1 小时 */
    private static final long WINDOW_MS = 60 * 60 * 1000L;
    /** 审计日志条数 */
    private static final int AUDIT_LIMIT = 20;

    private final FailureSnapshotMapper snapshotMapper;
    private final ServiceRegistry serviceRegistry;
    private final OpsServiceManager opsServiceManager;
    private final MetricSampleStore metricSampleStore;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 为告警事件生成快照并落库（幂等：同一事件已有快照则跳过）
     */
    public FailureSnapshotEntity buildAndSave(AlertEventEntity event) {
        List<FailureSnapshotEntity> existing = snapshotMapper.selectList(
                new LambdaQueryWrapper<FailureSnapshotEntity>()
                        .eq(FailureSnapshotEntity::getAlertEventId, event.getId())
                        .last("LIMIT 1"));
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        SnapshotData data = build(event);
        FailureSnapshotEntity entity = new FailureSnapshotEntity();
        entity.setAlertEventId(event.getId());
        entity.setRuleId(event.getRuleId());
        entity.setRuleName(event.getRuleName());
        entity.setService(event.getService());
        entity.setMetric(event.getMetric());
        entity.setStatus(event.getStatus());
        try {
            entity.setSnapshotJson(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            log.warn("快照 JSON 序列化失败 eventId={}: {}", event.getId(), e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "快照序列化失败");
        }
        snapshotMapper.insert(entity);
        return entity;
    }

    /**
     * 聚合快照数据（纯构建，不落库，便于测试）
     */
    public SnapshotData build(AlertEventEntity event) {
        long firedMs = event.getFiredAt() == null
                ? System.currentTimeMillis()
                : event.getFiredAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

        ServiceInfo info = serviceRegistry.getByName(event.getService());
        SnapshotData.ServiceView serviceView = info == null ? null
                : SnapshotData.ServiceView.builder()
                .name(info.getName())
                .baseUrl(info.getBaseUrl())
                .healthPath(info.getHealthPath())
                .metricsPath(info.getMetricsPath())
                .owner(info.getOwner())
                .env(info.getEnv())
                .build();

        Optional<OpsServiceEntity> svc = opsServiceManager.findByName(event.getService());
        SnapshotData.ServiceStatusView statusView = svc.map(s -> SnapshotData.ServiceStatusView.builder()
                .status(s.getStatus())
                .lastCheckedAt(s.getLastCheckedAt())
                .build()).orElse(null);

        List<MetricPoint> metricWindow = metricSampleStore.query(
                event.getService(), event.getMetric(), firedMs - WINDOW_MS, firedMs);

        List<AuditLogEntity> auditLogs = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLogEntity>()
                        .orderByDesc(AuditLogEntity::getCreatedAt)
                        .last("LIMIT " + AUDIT_LIMIT));

        return SnapshotData.builder()
                .event(SnapshotData.AlertEventView.builder()
                        .id(event.getId())
                        .ruleId(event.getRuleId())
                        .ruleName(event.getRuleName())
                        .service(event.getService())
                        .metric(event.getMetric())
                        .operator(event.getOperator())
                        .threshold(event.getThreshold())
                        .currentValue(event.getCurrentValue())
                        .status(event.getStatus())
                        .detail(event.getDetail())
                        .firedAt(event.getFiredAt())
                        .build())
                .serviceInfo(serviceView)
                .serviceStatus(statusView)
                .metricWindow(metricWindow)
                .auditLogs(auditLogs)
                .capturedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 按 ID 查询快照
     */
    public FailureSnapshotEntity getById(Long id) {
        FailureSnapshotEntity entity = snapshotMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SNAPSHOT_NOT_FOUND, "故障快照不存在: " + id);
        }
        return entity;
    }

    /**
     * 按服务 / 告警事件查询快照列表
     */
    public List<FailureSnapshotEntity> list(String service, Long alertEventId, int limit) {
        LambdaQueryWrapper<FailureSnapshotEntity> qw = new LambdaQueryWrapper<>();
        if (service != null && !service.isBlank()) {
            qw.eq(FailureSnapshotEntity::getService, service);
        }
        if (alertEventId != null) {
            qw.eq(FailureSnapshotEntity::getAlertEventId, alertEventId);
        }
        qw.orderByDesc(FailureSnapshotEntity::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        return snapshotMapper.selectList(qw);
    }
}
