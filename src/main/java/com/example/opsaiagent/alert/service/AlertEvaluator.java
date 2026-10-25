package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertEventStatus;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.alert.mapper.AlertRuleMapper;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.service.DiagnosisService;
import com.example.opsaiagent.maintenance.service.MaintenanceWindowService;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.store.MetricSampleStore;
import com.example.opsaiagent.notification.service.NotificationCenter;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.service.FailureSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警评估器（T06/T07/T08）。
 * 定时扫描所有启用规则：
 *   从持久化采样中取当前指标值，按 compareMode（THRESHOLD / YOY / SIGMA）判断是否命中
 *   命中需持续 duration_seconds 才触发，避免毛刺误报
 *   同一规则已有 FIRING 事件时不重复触发（去重）
 *   静默期 / 每小时节流内的事件以 SILENCED 状态落库
 *   不再命中时恢复最新 FIRING 事件为 RESOLVED
 *   触发 FIRING 时生成故障快照（T12）并通知启用渠道（T10）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvaluator {

    /** 同比基线窗口：取 24 小时前 ±2h 的采样 */
    private static final long YOY_BASELINE_WINDOW_MS = 2 * 60 * 60 * 1000L;
    /** σ 评估：取最近 1 小时采样 */
    private static final long SIGMA_WINDOW_MS = 60 * 60 * 1000L;

    private final AlertRuleMapper ruleMapper;
    private final AlertEventMapper eventMapper;
    private final MetricSampleStore metricSampleStore;
    private final AlertThrottleService throttle;
    private final NotificationCenter notificationCenter;
    private final FailureSnapshotService snapshotService;
    private final DiagnosisService diagnosisService;
    private final MaintenanceWindowService maintenanceWindowService;

    /** ruleId -> 连续命中起始时间戳（ms） */
    private final Map<Long, Long> sustainedSince = new ConcurrentHashMap<>();

    /**
     * 定时评估全部启用规则
     */
    @Scheduled(cron = "${ops-agent.alert.evaluate-cron:*/30 * * * * ?}")
    public void evaluate() {
        // 先处理维护窗口到期：恢复窗口内压制的告警，仍异常则重新通知
        recoverExpiredMaintenanceWindows();

        List<AlertRuleEntity> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<AlertRuleEntity>()
                        .eq(AlertRuleEntity::getEnabled, true));
        long now = System.currentTimeMillis();
        for (AlertRuleEntity rule : rules) {
            try {
                evaluateRule(rule, now);
            } catch (Exception e) {
                log.warn("告警评估失败 rule={}: {}", rule.getId(), e.getMessage());
            }
        }
    }

    /**
     * 维护窗口到期恢复（P1 规格 5）
     * 到期窗口置 ended_at；把窗口期间以维护静默压制的告警恢复为 FIRING，
     * 若指标仍命中则重新走闭环（快照/诊断/通知），否则恢复为 RESOLVED。
     */
    private void recoverExpiredMaintenanceWindows() {
        List<String> expiredServices = maintenanceWindowService.expireDueWindows(LocalDateTime.now());
        if (expiredServices.isEmpty()) {
            return;
        }
        List<AlertEventEntity> suppressed = eventMapper.selectList(
                new LambdaQueryWrapper<AlertEventEntity>()
                        .in(AlertEventEntity::getService, expiredServices)
                        .eq(AlertEventEntity::getStatus, AlertEventStatus.SILENCED.name())
                        .apply("silenced_reason LIKE '" + MaintenanceWindowService.SILENCE_REASON_PREFIX + "%'")
                        .last("LIMIT 200"));
        for (AlertEventEntity event : suppressed) {
            try {
                restoreMaintenanceEvent(event);
            } catch (Exception e) {
                log.warn("维护窗口告警恢复失败 eventId={}: {}", event.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 恢复单条维护压制告警：按规则重新评估是否仍命中。
     * 命中 → 转 FIRING 并触发闭环通知；不命中 → RESOLVED。
     */
    private void restoreMaintenanceEvent(AlertEventEntity event) {
        AlertRuleEntity rule = ruleMapper.selectById(event.getRuleId());
        LocalDateTime now = LocalDateTime.now();
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            // 规则已删除/停用：直接恢复为静默前状态（无规则可评估）
            event.setStatus(AlertEventStatus.RESOLVED.name());
            event.setResolvedAt(now);
            event.setUpdatedAt(now);
            eventMapper.updateById(event);
            return;
        }
        MetricPoint latest = latest(rule, rule.getService(), rule.getMetric(), System.currentTimeMillis());
        if (latest == null || !isHit(rule, latest.getValue(), System.currentTimeMillis())) {
            event.setStatus(AlertEventStatus.RESOLVED.name());
            event.setResolvedAt(now);
            event.setUpdatedAt(now);
            eventMapper.updateById(event);
            return;
        }
        event.setStatus(AlertEventStatus.FIRING.name());
        event.setSilencedReason(null);
        event.setSilencedUntil(null);
        event.setUpdatedAt(now);
        eventMapper.updateById(event);
        afterFiring(event);
    }

    /**
     * 评估单条规则
     */
    void evaluateRule(AlertRuleEntity rule, long nowMs) {
        String service = rule.getService();
        MetricPoint latest = latest(rule, service, rule.getMetric(), nowMs);
        if (latest == null) {
            // 无数据：无法评估，清除持续状态并尝试恢复（数据断流视为恢复）
            clearSustained(rule.getId());
            resolveFiring(rule.getId(), nowMs);
            return;
        }
        double value = latest.getValue();

        if (isHit(rule, value, nowMs)) {
            long start = sustainedSince.computeIfAbsent(rule.getId(), k -> nowMs);
            long sustainedSec = (nowMs - start) / 1000;
            int durationSec = rule.getDurationSeconds() == null ? 60 : rule.getDurationSeconds();
            if (sustainedSec < durationSec) {
                return; // 未达持续时长，等待下次评估
            }
            // 去重：已有 FIRING 事件则不重复触发
            if (findFiringId(rule.getId()) != null) {
                return;
            }
            // 维护窗口：命中但服务处于维护窗口 → 以维护静默落库，不通知不升级（P1 规格 5）
            if (maintenanceWindowService.isServiceInWindow(service, LocalDateTime.now())) {
                recordMaintenanceSuppressed(rule, value, nowMs);
                return;
            }
            // 静默 / 节流
            if (throttle.shouldSilence(rule.getId(), rule.getSilenceSeconds(), nowMs)) {
                recordEvent(rule, value, nowMs, AlertEventStatus.SILENCED, "静默期内压制，未重复告警");
                return;
            }
            if (!throttle.allowEvent(service, nowMs)) {
                recordEvent(rule, value, nowMs, AlertEventStatus.SILENCED, "超出每小时告警上限");
                return;
            }
            throttle.recordFired(rule.getId(), nowMs);
            throttle.recordEvent(service, nowMs);
            AlertEventEntity event = recordEvent(rule, value, nowMs, AlertEventStatus.FIRING, buildDetail(rule, value));
            afterFiring(event);
        } else {
            // 不再命中：清除持续状态并恢复
            clearSustained(rule.getId());
            resolveFiring(rule.getId(), nowMs);
        }
    }

    /**
     * 触发 FIRING 后闭环编排（T18）：生成故障快照 → LLM 智能诊断 → 通知启用渠道。
     * 每个环节独立容错：任一失败不中断后续环节，也不影响评估主流程。
     */
    private void afterFiring(AlertEventEntity event) {
        FailureSnapshotEntity snapshot = null;
        try {
            snapshot = snapshotService.buildAndSave(event);
        } catch (Exception e) {
            log.warn("故障快照生成失败 eventId={}: {}", event.getId(), e.getMessage());
        }

        DiagnosisRecordEntity diagnosis = null;
        if (snapshot != null) {
            try {
                diagnosis = diagnosisService.diagnose(snapshot);
            } catch (Exception e) {
                log.warn("LLM 诊断失败 eventId={}: {}", event.getId(), e.getMessage());
            }
        }

        try {
            notificationCenter.sendOnEvent(event, diagnosis);
        } catch (Exception e) {
            log.warn("告警通知发送失败 eventId={}: {}", event.getId(), e.getMessage());
        }
    }

    /**
     * 判断指标值是否命中规则
     */
    boolean isHit(AlertRuleEntity rule, double value, long nowMs) {
        String mode = rule.getCompareMode() == null ? "THRESHOLD" : rule.getCompareMode();
        return switch (mode) {
            case "YOY" -> hitYoy(rule, value, nowMs);
            case "SIGMA" -> hitSigma(rule, value, nowMs);
            default -> hitThreshold(rule, value);
        };
    }

    private boolean hitThreshold(AlertRuleEntity rule, double value) {
        boolean gt = "gt".equalsIgnoreCase(rule.getOperator());
        return gt ? value > rule.getThreshold() : value < rule.getThreshold();
    }

    private boolean hitYoy(AlertRuleEntity rule, double value, long nowMs) {
        // 基线 = 24 小时前窗口内最近一次采样
        long baselineEnd = nowMs - 24 * 60 * 60 * 1000L;
        MetricPoint baseline = latest(rule, rule.getService(), rule.getMetric(), baselineEnd);
        if (baseline == null || Math.abs(baseline.getValue()) < 1e-9) {
            return false;
        }
        double changePct = (value - baseline.getValue()) / Math.abs(baseline.getValue()) * 100;
        boolean gt = "gt".equalsIgnoreCase(rule.getOperator());
        return gt ? changePct > rule.getThreshold() : changePct < -rule.getThreshold();
    }

    private boolean hitSigma(AlertRuleEntity rule, double value, long nowMs) {
        List<MetricPoint> points = history(rule, rule.getService(), rule.getMetric(),
                nowMs - SIGMA_WINDOW_MS, nowMs);
        if (points.size() < 5) {
            return false; // 样本不足不评估
        }
        double mean = points.stream().mapToDouble(MetricPoint::getValue).average().orElse(0);
        double variance = points.stream()
                .mapToDouble(p -> Math.pow(p.getValue() - mean, 2))
                .average().orElse(0);
        double std = Math.sqrt(variance);
        if (std < 1e-9) {
            return false;
        }
        double z = (value - mean) / std;
        boolean gt = "gt".equalsIgnoreCase(rule.getOperator());
        return gt ? z > rule.getThreshold() : z < -rule.getThreshold();
    }

    /**
     * 按规则租户查询最新采样；租户为空时保持旧全局查询，兼容存量数据。
     */
    private MetricPoint latest(AlertRuleEntity rule, String service, String metric, long endMs) {
        return StringUtils.hasText(rule.getTenantId())
                ? metricSampleStore.queryLatest(rule.getTenantId(), service, metric, endMs)
                : metricSampleStore.queryLatest(service, metric, endMs);
    }

    /**
     * 按规则租户查询历史采样；租户为空时保持旧全局查询。
     */
    private List<MetricPoint> history(AlertRuleEntity rule, String service, String metric,
                                      long startMs, long endMs) {
        return StringUtils.hasText(rule.getTenantId())
                ? metricSampleStore.query(rule.getTenantId(), service, metric, startMs, endMs)
                : metricSampleStore.query(service, metric, startMs, endMs);
    }

    private Long findFiringId(Long ruleId) {
        List<AlertEventEntity> rows = eventMapper.selectList(
                new LambdaQueryWrapper<AlertEventEntity>()
                        .eq(AlertEventEntity::getRuleId, ruleId)
                        .eq(AlertEventEntity::getStatus, AlertEventStatus.FIRING.name())
                        .orderByDesc(AlertEventEntity::getFiredAt)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getId();
    }

    private void resolveFiring(Long ruleId, long nowMs) {
        List<AlertEventEntity> rows = eventMapper.selectList(
                new LambdaQueryWrapper<AlertEventEntity>()
                        .eq(AlertEventEntity::getRuleId, ruleId)
                        .eq(AlertEventEntity::getStatus, AlertEventStatus.FIRING.name())
                        .orderByDesc(AlertEventEntity::getFiredAt)
                        .last("LIMIT 1"));
        if (rows.isEmpty()) {
            return;
        }
        AlertEventEntity event = rows.get(0);
        event.setStatus(AlertEventStatus.RESOLVED.name());
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        eventMapper.updateById(event);
    }

    private AlertEventEntity recordEvent(AlertRuleEntity rule, double value, long nowMs,
                             AlertEventStatus status, String detail) {
        AlertEventEntity event = new AlertEventEntity();
        event.setRuleId(rule.getId());
        event.setTenantId(rule.getTenantId());
        event.setDeptId(rule.getDeptId());
        event.setRuleName(rule.getName());
        event.setService(rule.getService());
        event.setMetric(rule.getMetric());
        event.setOperator(rule.getOperator());
        event.setThreshold(rule.getThreshold());
        event.setCurrentValue(value);
        event.setStatus(status.name());
        event.setDetail(detail);
        event.setFiredAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        eventMapper.insert(event);
        return event;
    }

    /**
     * 维护窗口压制落库（P1 规格 5）
     * 服务在维护窗口内命中规则时：告警照常落库（SILENCED），但不通知不升级。
     * silenced_reason 打上维护窗口前缀，便于到期扫描识别并恢复。
     */
    private AlertEventEntity recordMaintenanceSuppressed(AlertRuleEntity rule, double value, long nowMs) {
        AlertEventEntity event = new AlertEventEntity();
        event.setRuleId(rule.getId());
        event.setTenantId(rule.getTenantId());
        event.setDeptId(rule.getDeptId());
        event.setRuleName(rule.getName());
        event.setService(rule.getService());
        event.setMetric(rule.getMetric());
        event.setOperator(rule.getOperator());
        event.setThreshold(rule.getThreshold());
        event.setCurrentValue(value);
        event.setStatus(AlertEventStatus.SILENCED.name());
        event.setDetail("维护窗口期内命中，已压制通知与升级（P1 维护窗口）");
        event.setSilencedFrom(AlertEventStatus.FIRING.name());
        event.setSilencedReason(MaintenanceWindowService.SILENCE_REASON_PREFIX + "维护窗口");
        event.setSilencedAt(LocalDateTime.now());
        event.setFiredAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        eventMapper.insert(event);
        return event;
    }

    private String buildDetail(AlertRuleEntity rule, double value) {
        return String.format("规则[%s] 指标[%s] 当前值=%.3f %s 阈值=%.3f（%s）",
                rule.getName(), rule.getMetric(), value,
                "gt".equalsIgnoreCase(rule.getOperator()) ? ">" : "<",
                rule.getThreshold(),
                rule.getCompareMode() == null ? "THRESHOLD" : rule.getCompareMode());
    }

    private void clearSustained(Long ruleId) {
        sustainedSince.remove(ruleId);
    }
}
