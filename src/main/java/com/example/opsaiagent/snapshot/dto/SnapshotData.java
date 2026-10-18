package com.example.opsaiagent.snapshot.dto;

import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 故障快照聚合结构（T12）
 * 告警触发时采集：事件信息 + 服务状态 + 指标窗口 + 服务信息 + 审计日志，序列化为 JSON 存储。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotData {

    /** 告警事件关键信息 */
    private AlertEventView event;

    /** 服务注册信息 */
    private ServiceView serviceInfo;

    /** 服务当前健康状态 */
    private ServiceStatusView serviceStatus;

    /** 触发前指标窗口（近 1 小时） */
    private List<MetricPoint> metricWindow;

    /** 最近审计日志 */
    private List<AuditLogEntity> auditLogs;

    /** 快照采集时间 */
    private LocalDateTime capturedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertEventView {
        private Long id;
        private Long ruleId;
        private String ruleName;
        private String service;
        private String metric;
        private String operator;
        private Double threshold;
        private Double currentValue;
        private String status;
        private String detail;
        private LocalDateTime firedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceView {
        private String name;
        private String baseUrl;
        private String healthPath;
        private String metricsPath;
        private String owner;
        private String env;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceStatusView {
        private String status;
        private LocalDateTime lastCheckedAt;
    }
}
