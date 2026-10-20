package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.entity.AlertEscalationRecordEntity;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.entity.AlertRuleEscalationLevelEntity;
import com.example.opsaiagent.alert.mapper.AlertEscalationRecordMapper;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.alert.mapper.AlertRuleEscalationLevelMapper;
import com.example.opsaiagent.alert.mapper.AlertRuleMapper;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.enums.AuditOperation;
import com.example.opsaiagent.audit.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertEscalationService 单元测试
 * 覆盖（P0 规格 4）：
 * - 扫描时先执行静默到期恢复
 * - FIRING + UNACKNOWLEDGED 到期升级：更新事件级别 + 写升级记录 + 审计
 * - 未到期不升级 / 已升级级别跳过
 * - ACK + UNRESOLVED 以 acknowledgedAt 为基准
 * - 无启用规则 / 无级别 / 无待处理事件路径跳过
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警升级服务")
class AlertEscalationServiceTest {

    @Mock
    private AlertEventMapper eventMapper;
    @Mock
    private AlertRuleMapper ruleMapper;
    @Mock
    private AlertRuleEscalationLevelMapper levelMapper;
    @Mock
    private AlertEscalationRecordMapper escalationMapper;
    @Mock
    private AlertEventService eventService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AlertEscalationService service;

    private AlertRuleEntity rule(Long id) {
        AlertRuleEntity r = new AlertRuleEntity();
        r.setId(id);
        r.setEnabled(true);
        r.setEscalationEnabled(true);
        return r;
    }

    private AlertRuleEscalationLevelEntity level(int no, int after, String triggerOn, String toRole) {
        AlertRuleEscalationLevelEntity l = new AlertRuleEscalationLevelEntity();
        l.setRuleId(1L);
        l.setLevelNo(no);
        l.setAfterSeconds(after);
        l.setTriggerOn(triggerOn);
        l.setToRole(toRole);
        return l;
    }

    private AlertEventEntity event(Long id, Long ruleId, String status, LocalDateTime firedAt) {
        AlertEventEntity e = new AlertEventEntity();
        e.setId(id);
        e.setRuleId(ruleId);
        e.setStatus(status);
        e.setFiredAt(firedAt);
        return e;
    }

    @Test
    @DisplayName("scanAndEscalate：先执行静默到期恢复")
    void scan_restoresExpiredSilencesFirst() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.scanAndEscalate();

        verify(eventService).restoreExpiredSilences();
    }

    @Test
    @DisplayName("scanAndEscalate：无启用规则直接跳过")
    void scan_noRules() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.scanAndEscalate();

        verify(levelMapper, never()).selectList(any(Wrapper.class));
        verify(eventMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("scanAndEscalate：规则无级别跳过事件扫描")
    void scan_ruleWithoutLevels() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.scanAndEscalate();

        verify(eventMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("scanAndEscalate：FIRING 超时升级并写记录+审计")
    void scan_firingEscalates() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(level(1, 300, "UNACKNOWLEDGED", "OPERATOR")));
        AlertEventEntity e = event(10L, 1L, "FIRING", LocalDateTime.now().minusMinutes(10));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        service.scanAndEscalate();

        // 事件升级到 L1
        assertThat(e.getEscalationLevel()).isEqualTo(1);
        assertThat(e.getEscalatedAt()).isNotNull();
        verify(eventMapper).updateById(e);

        // 升级记录
        ArgumentCaptor<AlertEscalationRecordEntity> recordCaptor =
                ArgumentCaptor.forClass(AlertEscalationRecordEntity.class);
        verify(escalationMapper).insert(recordCaptor.capture());
        AlertEscalationRecordEntity record = recordCaptor.getValue();
        assertThat(record.getAlertEventId()).isEqualTo(10L);
        assertThat(record.getLevel()).isEqualTo(1);
        assertThat(record.getTriggerOn()).isEqualTo("UNACKNOWLEDGED");
        assertThat(record.getToRole()).isEqualTo("OPERATOR");
        assertThat(record.getReason()).contains("5 分钟未确认");

        // 审计
        ArgumentCaptor<AuditLogEntity> auditCaptor =
                ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).save(auditCaptor.capture());
        AuditLogEntity audit = auditCaptor.getValue();
        assertThat(audit.getUsername()).isEqualTo("system");
        assertThat(audit.getOperation()).isEqualTo(AuditOperation.ESCALATE_ALERT_EVENT.name());
        assertThat(audit.getTarget()).isEqualTo("alert_event:10");
        assertThat(audit.getResult()).contains("升级到级别 1");
    }

    @Test
    @DisplayName("scanAndEscalate：未到期不升级")
    void scan_notDueNoEscalation() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(level(1, 300, "UNACKNOWLEDGED", "OPERATOR")));
        AlertEventEntity e = event(11L, 1L, "FIRING", LocalDateTime.now().minusMinutes(1));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        service.scanAndEscalate();

        assertThat(e.getEscalationLevel()).isNull();
        verify(eventMapper, never()).updateById(e);
        verify(escalationMapper, never()).insert(any(AlertEscalationRecordEntity.class));
        verify(auditLogService, never()).save(any(AuditLogEntity.class));
    }

    @Test
    @DisplayName("scanAndEscalate：ACK + UNRESOLVED 以 acknowledgedAt 为基准到期升级")
    void scan_ackUnresolvedBaseIsAcknowledgedAt() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(level(1, 300, "UNRESOLVED", "ADMIN")));
        // firedAt 较久但 acknowledgedAt 是 1 分钟前 -> 未到 5 分钟
        AlertEventEntity e = event(12L, 1L, "ACKNOWLEDGED",
                LocalDateTime.now().minusHours(2));
        e.setAcknowledgedAt(LocalDateTime.now().minusMinutes(1));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        service.scanAndEscalate();

        assertThat(e.getEscalationLevel()).isNull();

        // acknowledgedAt 10 分钟前 -> 到期升级
        AlertEventEntity e2 = event(13L, 1L, "ACKNOWLEDGED",
                LocalDateTime.now().minusHours(2));
        e2.setAcknowledgedAt(LocalDateTime.now().minusMinutes(10));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e2));

        service.scanAndEscalate();

        assertThat(e2.getEscalationLevel()).isEqualTo(1);
        verify(escalationMapper, times(1)).insert(any(AlertEscalationRecordEntity.class));
    }

    @Test
    @DisplayName("scanAndEscalate：UNACKNOWLEDGED 条件不匹配 ACK 事件")
    void scan_unackTriggerSkipsAckEvent() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(level(1, 300, "UNACKNOWLEDGED", "OPERATOR")));
        AlertEventEntity e = event(14L, 1L, "ACKNOWLEDGED", LocalDateTime.now().minusHours(2));
        e.setAcknowledgedAt(LocalDateTime.now().minusHours(1));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        service.scanAndEscalate();

        assertThat(e.getEscalationLevel()).isNull();
        verify(eventMapper, never()).updateById(e);
    }

    @Test
    @DisplayName("scanAndEscalate：已升级到 L1 后不重复升级到更高级别（同触发类型未到期）")
    void scan_skipsAlreadyEscalatedLevels() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        level(1, 300, "UNACKNOWLEDGED", "OPERATOR"),
                        level(2, 600, "UNACKNOWLEDGED", "ADMIN")));
        // firedAt 8 分钟前：L1 已升级（currentLevel=1），L2 未到期（600s）-> 不升级
        AlertEventEntity e = event(15L, 1L, "FIRING", LocalDateTime.now().minusMinutes(8));
        e.setEscalationLevel(1);
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        service.scanAndEscalate();

        assertThat(e.getEscalationLevel()).isEqualTo(1);
        verify(eventMapper, never()).updateById(e);
        verify(escalationMapper, never()).insert(any(AlertEscalationRecordEntity.class));
    }

    @Test
    @DisplayName("scanAndEscalate：L1 已升级且 L2 也到期时升级到 L2")
    void scan_escalatesToNextDueLevel() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        level(1, 300, "UNACKNOWLEDGED", "OPERATOR"),
                        level(2, 600, "UNACKNOWLEDGED", "ADMIN")));
        // firedAt 20 分钟前（1200s）：跳过 L1，L2 到期 -> 升级到 L2
        AlertEventEntity e = event(16L, 1L, "FIRING", LocalDateTime.now().minusMinutes(20));
        e.setEscalationLevel(1);
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        service.scanAndEscalate();

        assertThat(e.getEscalationLevel()).isEqualTo(2);
        ArgumentCaptor<AlertEscalationRecordEntity> captor =
                ArgumentCaptor.forClass(AlertEscalationRecordEntity.class);
        verify(escalationMapper).insert(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo(2);
        assertThat(captor.getValue().getToRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("scanAndEscalate：事件 ruleId 无对应级别跳过")
    void scan_eventWithoutLevelsSkipped() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(level(1, 300, "UNACKNOWLEDGED", "OPERATOR")));
        AlertEventEntity e = event(17L, 99L, "FIRING", LocalDateTime.now().minusMinutes(10));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e));

        service.scanAndEscalate();

        verify(eventMapper, never()).updateById(e);
        verify(escalationMapper, never()).insert(any(AlertEscalationRecordEntity.class));
    }

    @Test
    @DisplayName("scanAndEscalate：无待处理事件不写记录")
    void scan_noEvents() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(1L)));
        when(levelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(level(1, 300, "UNACKNOWLEDGED", "OPERATOR")));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.scanAndEscalate();

        verify(escalationMapper, never()).insert(any(AlertEscalationRecordEntity.class));
        verify(auditLogService, never()).save(any(AuditLogEntity.class));
    }
}
