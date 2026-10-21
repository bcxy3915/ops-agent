package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertEvaluator 单元测试
 * 覆盖：阈值触发 / 未达持续时长 / 去重 / 静默 / 每小时节流 / 恢复 / 同比 / 3σ / 无数据 / 整体容错
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警评估器")
class AlertEvaluatorTest {

    @Mock
    private AlertRuleMapper ruleMapper;

    @Mock
    private AlertEventMapper eventMapper;

    @Mock
    private MetricSampleStore metricSampleStore;

    @Mock
    private AlertThrottleService throttle;

    @Mock
    private NotificationCenter notificationCenter;

    @Mock
    private FailureSnapshotService snapshotService;

    @Mock
    private DiagnosisService diagnosisService;

    @Mock
    private MaintenanceWindowService maintenanceWindowService;

    @InjectMocks
    private AlertEvaluator evaluator;

    private static final long NOW = 1_700_000_000_000L;

    @BeforeEach
    void setUp() {
        // 默认允许产生事件；个别用例单独覆盖（静默 / 节流用例会短路或覆盖该 stub）
        lenient().when(throttle.allowEvent(anyString(), anyLong())).thenReturn(true);
        // 默认无到期维护窗口、服务不在维护窗口内（evaluate() 开头与命中分支会查询）
        lenient().when(maintenanceWindowService.expireDueWindows(any())).thenReturn(java.util.List.of());
        lenient().when(maintenanceWindowService.isServiceInWindow(anyString(), any())).thenReturn(false);
    }

    private AlertRuleEntity rule(Long id, String mode, String operator, double threshold, int durationSec) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(id);
        rule.setName("cpu-high");
        rule.setService("svc-a");
        rule.setMetric("cpu");
        rule.setOperator(operator);
        rule.setThreshold(threshold);
        rule.setCompareMode(mode);
        rule.setDurationSeconds(durationSec);
        rule.setSilenceSeconds(0);
        rule.setEnabled(true);
        return rule;
    }

    private AlertEventEntity firingEvent(Long id) {
        AlertEventEntity e = new AlertEventEntity();
        e.setId(id);
        e.setRuleId(1L);
        e.setStatus("FIRING");
        return e;
    }

    private AlertEventEntity captureInserted() {
        ArgumentCaptor<AlertEventEntity> captor = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(eventMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== THRESHOLD ====================

    @Test
    @DisplayName("阈值模式：命中且无 FIRING 时创建 FIRING 事件")
    void threshold_hitCreatesFiring() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        FailureSnapshotEntity snap = new FailureSnapshotEntity();
        snap.setId(10L);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 95.0));
        when(snapshotService.buildAndSave(any())).thenReturn(snap);

        evaluator.evaluateRule(rule, NOW);

        AlertEventEntity inserted = captureInserted();
        assertThat(inserted.getStatus()).isEqualTo("FIRING");
        assertThat(inserted.getCurrentValue()).isEqualTo(95.0);
        assertThat(inserted.getThreshold()).isEqualTo(80.0);
        assertThat(inserted.getFiredAt()).isNotNull();
        verify(throttle).recordFired(1L, NOW);
        verify(throttle).recordEvent("svc-a", NOW);
        // 闭环编排（T18）：快照生成成功 → LLM 诊断 → 通知（带诊断结果）
        verify(snapshotService).buildAndSave(inserted);
        verify(diagnosisService).diagnose(snap);
        verify(notificationCenter).sendOnEvent(eq(inserted), any());
    }

    @Test
    @DisplayName("闭环编排：快照生成失败时跳过诊断，仍发送通知（降级）")
    void afterFiring_snapshotFails_skipDiagnosis() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 95.0));
        when(snapshotService.buildAndSave(any())).thenThrow(new RuntimeException("snapshot fail"));

        evaluator.evaluateRule(rule, NOW);

        verify(diagnosisService, never()).diagnose(any());
        verify(notificationCenter).sendOnEvent(any(), isNull());
    }

    @Test
    @DisplayName("闭环编排：诊断失败时仍发送通知（降级）")
    void afterFiring_diagnosisFails_stillNotify() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        FailureSnapshotEntity snap = new FailureSnapshotEntity();
        snap.setId(10L);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 95.0));
        when(snapshotService.buildAndSave(any())).thenReturn(snap);
        when(diagnosisService.diagnose(any())).thenThrow(new RuntimeException("llm fail"));

        evaluator.evaluateRule(rule, NOW);

        verify(notificationCenter).sendOnEvent(any(), isNull());
    }

    @Test
    @DisplayName("闭环编排：诊断成功时把诊断结果传给通知")
    void afterFiring_diagnosisSuccess_passesDiagnosis() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        FailureSnapshotEntity snap = new FailureSnapshotEntity();
        snap.setId(10L);
        DiagnosisRecordEntity diag = new DiagnosisRecordEntity();
        diag.setStatus("SUCCESS");
        diag.setRootCause("CPU 打满");
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 95.0));
        when(snapshotService.buildAndSave(any())).thenReturn(snap);
        when(diagnosisService.diagnose(any())).thenReturn(diag);

        evaluator.evaluateRule(rule, NOW);

        verify(notificationCenter).sendOnEvent(any(), eq(diag));
    }

    @Test
    @DisplayName("阈值模式：低于阈值不触发")
    void threshold_notHitNoEvent() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 30.0));

        evaluator.evaluateRule(rule, NOW);

        verify(eventMapper, never()).insert(any(AlertEventEntity.class));
    }

    @Test
    @DisplayName("阈值模式：lt 比较符方向正确")
    void threshold_ltDirection() {
        AlertRuleEntity rule = rule(2L, "THRESHOLD", "lt", 10, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 5.0));

        evaluator.evaluateRule(rule, NOW);

        assertThat(captureInserted().getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("持续时长：未达持续秒数不触发，持续达标后触发")
    void duration_requiresSustained() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 60);
        when(metricSampleStore.queryLatest(eq("svc-a"), eq("cpu"), anyLong()))
                .thenReturn(new MetricPoint(NOW, 95.0));

        // 第一次评估：持续 0 秒，不触发
        evaluator.evaluateRule(rule, NOW);
        verify(eventMapper, never()).insert(any(AlertEventEntity.class));

        // 60 秒后：持续达 60 秒，触发
        evaluator.evaluateRule(rule, NOW + 60_000);
        assertThat(captureInserted().getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("去重：已有 FIRING 事件时不重复创建")
    void dedup_existingFiring() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 95.0));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(firingEvent(10L)));

        evaluator.evaluateRule(rule, NOW);

        verify(eventMapper, never()).insert(any(AlertEventEntity.class));
    }

    @Test
    @DisplayName("静默：静默期内事件以 SILENCED 落库且不重复告警")
    void silence_recordsSilenced() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        rule.setSilenceSeconds(300);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 95.0));
        when(throttle.shouldSilence(1L, 300, NOW)).thenReturn(true);

        evaluator.evaluateRule(rule, NOW);

        AlertEventEntity inserted = captureInserted();
        assertThat(inserted.getStatus()).isEqualTo("SILENCED");
    }

    @Test
    @DisplayName("节流：超出每小时上限时事件以 SILENCED 落库")
    void throttle_overLimitSilenced() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 95.0));
        when(throttle.allowEvent("svc-a", NOW)).thenReturn(false);

        evaluator.evaluateRule(rule, NOW);

        assertThat(captureInserted().getStatus()).isEqualTo("SILENCED");
    }

    @Test
    @DisplayName("恢复：不再命中时最新 FIRING 事件变为 RESOLVED")
    void recovery_resolvesFiring() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 30.0));
        AlertEventEntity firing = firingEvent(10L);
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(firing));

        evaluator.evaluateRule(rule, NOW);

        assertThat(firing.getStatus()).isEqualTo("RESOLVED");
        assertThat(firing.getResolvedAt()).isNotNull();
        verify(eventMapper).updateById(firing);
    }

    @Test
    @DisplayName("无数据：不触发也不报错")
    void noData_skips() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW)).thenReturn(null);

        evaluator.evaluateRule(rule, NOW);

        verify(eventMapper, never()).insert(any(AlertEventEntity.class));
    }

    // ==================== YOY / SIGMA ====================

    @Test
    @DisplayName("同比模式：增长率超阈值触发")
    void yoy_growthTriggers() {
        AlertRuleEntity rule = rule(1L, "YOY", "gt", 10, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 120.0));
        // 基线 100：增长 20% > 10%
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW - 24 * 3600_000L))
                .thenReturn(new MetricPoint(NOW - 24 * 3600_000L, 100.0));

        evaluator.evaluateRule(rule, NOW);

        assertThat(captureInserted().getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("同比模式：无基线数据不触发")
    void yoy_noBaseline() {
        AlertRuleEntity rule = rule(1L, "YOY", "gt", 10, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 120.0));
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW - 24 * 3600_000L))
                .thenReturn(null);

        evaluator.evaluateRule(rule, NOW);

        verify(eventMapper, never()).insert(any(AlertEventEntity.class));
    }

    @Test
    @DisplayName("3σ 模式：偏离均值超过 threshold 个标准差触发")
    void sigma_deviationTriggers() {
        AlertRuleEntity rule = rule(1L, "SIGMA", "gt", 3, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 50.0));
        // 样本均值约 10，标准差很小，当前值 50 远超
        List<MetricPoint> samples = List.of(
                new MetricPoint(NOW - 3000, 10.0),
                new MetricPoint(NOW - 2000, 10.0),
                new MetricPoint(NOW - 1000, 10.0),
                new MetricPoint(NOW - 500, 11.0),
                new MetricPoint(NOW - 100, 10.0));
        when(metricSampleStore.query("svc-a", "cpu", NOW - 3600_000L, NOW))
                .thenReturn(samples);

        evaluator.evaluateRule(rule, NOW);

        assertThat(captureInserted().getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("3σ 模式：样本不足 5 个不触发")
    void sigma_insufficientSamples() {
        AlertRuleEntity rule = rule(1L, "SIGMA", "gt", 3, 0);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 50.0));
        when(metricSampleStore.query("svc-a", "cpu", NOW - 3600_000L, NOW))
                .thenReturn(List.of(new MetricPoint(NOW - 100, 10.0)));

        evaluator.evaluateRule(rule, NOW);

        verify(eventMapper, never()).insert(any(AlertEventEntity.class));
    }

    // ==================== P1 维护窗口 ====================

    @Test
    @DisplayName("维护窗口：服务在窗口内命中 → SILENCED 落库，不触发通知/快照/诊断")
    void maintenanceWindow_hitSuppressedNoNotify() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(maintenanceWindowService.isServiceInWindow(eq("svc-a"), any())).thenReturn(true);
        when(metricSampleStore.queryLatest(eq("svc-a"), eq("cpu"), anyLong()))
                .thenReturn(new MetricPoint(NOW, 90.0));

        evaluator.evaluateRule(rule, NOW);

        AlertEventEntity inserted = captureInserted();
        assertThat(inserted.getStatus()).isEqualTo("SILENCED");
        assertThat(inserted.getSilencedReason()).startsWith(MaintenanceWindowService.SILENCE_REASON_PREFIX);
        verify(notificationCenter, never()).sendOnEvent(any(), any());
        verify(snapshotService, never()).buildAndSave(any());
    }

    @Test
    @DisplayName("维护窗口：窗口外命中 → 正常 FIRING 并走闭环")
    void maintenanceWindow_notInWindowFiresNormally() {
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(maintenanceWindowService.isServiceInWindow(eq("svc-a"), any())).thenReturn(false);
        when(metricSampleStore.queryLatest("svc-a", "cpu", NOW))
                .thenReturn(new MetricPoint(NOW, 90.0));

        evaluator.evaluateRule(rule, NOW);

        assertThat(captureInserted().getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("维护窗口到期：仍命中 → 恢复 FIRING 并重新触发闭环")
    void maintenanceWindow_expireStillHitRestoresFiring() {
        when(maintenanceWindowService.expireDueWindows(any()))
                .thenReturn(java.util.List.of("svc-a"));
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(ruleMapper.selectById(1L)).thenReturn(rule);
        when(metricSampleStore.queryLatest(eq("svc-a"), eq("cpu"), anyLong()))
                .thenReturn(new MetricPoint(NOW, 90.0));

        AlertEventEntity suppressed = new AlertEventEntity();
        suppressed.setId(10L);
        suppressed.setRuleId(1L);
        suppressed.setService("svc-a");
        suppressed.setStatus("SILENCED");
        suppressed.setSilencedReason(MaintenanceWindowService.SILENCE_REASON_PREFIX + "维护窗口");
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(suppressed));

        evaluator.evaluate();

        verify(eventMapper).updateById(argThat(e -> "FIRING".equals(e.getStatus())));
        verify(notificationCenter, org.mockito.Mockito.atLeastOnce()).sendOnEvent(any(), any());
    }

    @Test
    @DisplayName("维护窗口到期：指标已恢复 → 直接 RESOLVED，不通知")
    void maintenanceWindow_expireRecoveredResolves() {
        when(maintenanceWindowService.expireDueWindows(any()))
                .thenReturn(java.util.List.of("svc-a"));
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(ruleMapper.selectById(1L)).thenReturn(rule);
        when(metricSampleStore.queryLatest(eq("svc-a"), eq("cpu"), anyLong()))
                .thenReturn(new MetricPoint(NOW, 30.0));

        AlertEventEntity suppressed = new AlertEventEntity();
        suppressed.setId(11L);
        suppressed.setRuleId(1L);
        suppressed.setService("svc-a");
        suppressed.setStatus("SILENCED");
        suppressed.setSilencedReason(MaintenanceWindowService.SILENCE_REASON_PREFIX + "维护窗口");
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(suppressed));

        evaluator.evaluate();

        verify(eventMapper).updateById(argThat(e -> "RESOLVED".equals(e.getStatus())));
        verify(notificationCenter, never()).sendOnEvent(any(), any());
    }

    // ==================== evaluate() ====================

    @Test
    @DisplayName("evaluate：只评估启用规则，异常规则不中断整体")
    void evaluate_onlyEnabledAndTolerant() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        evaluator.evaluate();
        verify(eventMapper, never()).insert(any(AlertEventEntity.class));

        // 单条规则评估异常不影响继续
        AlertRuleEntity rule = rule(1L, "THRESHOLD", "gt", 80, 0);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(metricSampleStore.queryLatest(eq("svc-a"), eq("cpu"), anyLong()))
                .thenThrow(new RuntimeException("boom"));

        evaluator.evaluate();
        // 异常被吞掉，未产生事件
        verify(eventMapper, never()).insert(any(AlertEventEntity.class));
        verify(eventMapper, never()).selectList(any(Wrapper.class));
    }
}
