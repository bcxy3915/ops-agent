package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertEventStatus;
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
import com.example.opsaiagent.oncall.service.OncallMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警升级服务（P0 决策 3 / 规格 4）
 * - 每 30s 扫描 FIRING / ACKNOWLEDGED 事件，按规则多级升级路径自动升级
 * - 升级记录写 alert_escalation + 审计日志
 * - 顺带执行静默到期自动恢复（规格 2.1）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEscalationService {

    private final AlertEventMapper eventMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertRuleEscalationLevelMapper levelMapper;
    private final AlertEscalationRecordMapper escalationMapper;
    private final AlertEventService eventService;
    private final AuditLogService auditLogService;
    private final OncallMemberService oncallMemberService;

    /**
     * 定时扫描：升级 + 静默到期恢复（规格 4.4：30s）
     */
    @Scheduled(cron = "${ops-agent.alert.escalation-cron:*/30 * * * * ?}")
    public void scanAndEscalate() {
        // 静默到期自动回原状态
        eventService.restoreExpiredSilences();

        LocalDateTime now = LocalDateTime.now();
        List<AlertRuleEntity> rules = ruleMapper.selectList(new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getEnabled, true)
                .eq(AlertRuleEntity::getEscalationEnabled, true));
        if (rules.isEmpty()) {
            return;
        }
        Map<Long, List<AlertRuleEscalationLevelEntity>> levelMap = new HashMap<>();
        for (AlertRuleEntity rule : rules) {
            List<AlertRuleEscalationLevelEntity> levels = levelMapper.selectList(
                    new LambdaQueryWrapper<AlertRuleEscalationLevelEntity>()
                            .eq(AlertRuleEscalationLevelEntity::getRuleId, rule.getId())
                            .orderByAsc(AlertRuleEscalationLevelEntity::getLevelNo));
            if (!levels.isEmpty()) {
                levelMap.put(rule.getId(), levels);
            }
        }
        if (levelMap.isEmpty()) {
            return;
        }
        List<AlertEventEntity> events = eventMapper.selectList(
                new LambdaQueryWrapper<AlertEventEntity>()
                        .in(AlertEventEntity::getStatus,
                                AlertEventStatus.FIRING.name(),
                                AlertEventStatus.ACKNOWLEDGED.name())
                        .last("LIMIT 500"));
        for (AlertEventEntity event : events) {
            List<AlertRuleEscalationLevelEntity> levels = levelMap.get(event.getRuleId());
            if (levels == null || levels.isEmpty()) {
                continue;
            }
            escalateIfDue(event, levels, now);
        }
    }

    /**
     * 判断并执行升级（每次最多升到下一个到期级别）
     */
    private void escalateIfDue(AlertEventEntity event,
                               List<AlertRuleEscalationLevelEntity> levels,
                               LocalDateTime now) {
        boolean isFiring = AlertEventStatus.FIRING.name().equals(event.getStatus());
        boolean isAck = AlertEventStatus.ACKNOWLEDGED.name().equals(event.getStatus());
        int currentLevel = event.getEscalationLevel() == null ? 0 : event.getEscalationLevel();
        for (AlertRuleEscalationLevelEntity level : levels) {
            if (level.getLevelNo() == null || level.getLevelNo() <= currentLevel) {
                continue;
            }
            String triggerOn = level.getTriggerOn() == null ? "UNACKNOWLEDGED" : level.getTriggerOn();
            boolean matched = "UNRESOLVED".equals(triggerOn) ? isAck : isFiring;
            if (!matched) {
                continue;
            }
            LocalDateTime base = "UNRESOLVED".equals(triggerOn)
                    ? (event.getAcknowledgedAt() != null ? event.getAcknowledgedAt() : event.getFiredAt())
                    : event.getFiredAt();
            if (base == null) {
                continue;
            }
            int after = level.getAfterSeconds() == null ? 0 : level.getAfterSeconds();
            if (Duration.between(base, now).getSeconds() >= after) {
                escalate(event, level, now);
                break;
            }
        }
    }

    /**
     * 执行升级：更新事件级别 + 写升级记录 + 审计
     */
    private void escalate(AlertEventEntity event, AlertRuleEscalationLevelEntity level, LocalDateTime now) {
        event.setEscalationLevel(level.getLevelNo());
        event.setEscalatedAt(now);
        event.setUpdatedAt(now);
        eventMapper.updateById(event);

        String triggerOn = level.getTriggerOn() == null ? "UNACKNOWLEDGED" : level.getTriggerOn();
        int minutes = (level.getAfterSeconds() == null ? 0 : level.getAfterSeconds()) / 60;
        String reason = "UNRESOLVED".equals(triggerOn)
                ? minutes + " 分钟未恢复，按规则自动升级"
                : minutes + " 分钟未确认，按规则自动升级";

        AlertEscalationRecordEntity record = new AlertEscalationRecordEntity();
        record.setAlertEventId(event.getId());
        record.setLevel(level.getLevelNo());
        record.setTriggerOn(triggerOn);
        record.setToRole(level.getToRole());
        record.setToUsers(level.getToUsers());
        record.setReason(reason);
        record.setTriggeredAt(now);
        escalationMapper.insert(record);

        // P1 值班联动：规则显式 toUsers 优先，否则按级别取值班人（L1→备值班、L2→ADMIN）
        List<Long> recipients = event.getTenantId() == null
                ? oncallMemberService.resolveRecipients(
                        level.getToUsers(), level.getToRole(), level.getLevelNo())
                : oncallMemberService.resolveRecipients(event.getTenantId(),
                        level.getToUsers(), level.getToRole(), level.getLevelNo());
        String recipientDesc = recipients.isEmpty()
                ? (level.getToUsers() != null && !level.getToUsers().isBlank()
                        ? level.getToUsers() : (level.getToRole() == null ? "角色兜底" : level.getToRole()))
                : recipients.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        AuditLogEntity audit = new AuditLogEntity();
        audit.setUsername("system");
        audit.setOperation(AuditOperation.ESCALATE_ALERT_EVENT.name());
        audit.setTarget("alert_event:" + event.getId());
        audit.setMethod("AlertEscalationService.escalate");
        audit.setResult("升级到级别 " + level.getLevelNo()
                + (level.getToRole() != null ? "，通知角色 " + level.getToRole() : "")
                + "，收件人 " + recipientDesc);
        audit.setCreatedAt(now);
        auditLogService.save(audit);

        log.info("告警事件 {} 自动升级到级别 {}（{}），收件人 {}", event.getId(), level.getLevelNo(), reason, recipientDesc);
    }
}
