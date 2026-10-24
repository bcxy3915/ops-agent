package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertEventResponse;
import com.example.opsaiagent.alert.dto.AlertEventStatus;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警事件服务（查询 / 确认 / 状态流转）
 * P0 状态机（规格 2.1）：FIRING → ACKNOWLEDGED → RESOLVED；任意态可 SILENCED（到期/解除回原状态）
 * 认领（规格 3）：acknowledge 自动认领 + 独立 claim；接管他人告警可覆盖 owner
 */
@Service
@RequiredArgsConstructor
public class AlertEventService {

    /** 静默到期时间下限：当前时间 + 15 分钟（规格 2.5） */
    private static final long MIN_SILENCE_MINUTES = 15;

    private final AlertEventMapper mapper;
    private final AlertReadService readService;

    /**
     * 查询事件列表（可按服务 / 状态 / 规则过滤）
     */
    public List<AlertEventEntity> list(String service, String status, Long ruleId, int limit) {
        LambdaQueryWrapper<AlertEventEntity> qw = new LambdaQueryWrapper<>();
        if (service != null && !service.isBlank()) {
            qw.eq(AlertEventEntity::getService, service);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(AlertEventEntity::getStatus, status);
        }
        if (ruleId != null) {
            qw.eq(AlertEventEntity::getRuleId, ruleId);
        }
        qw.orderByDesc(AlertEventEntity::getFiredAt)
                .orderByDesc(AlertEventEntity::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        return mapper.selectList(qw);
    }

    /**
     * 按 ID 查询
     */
    public AlertEventEntity getById(Long id) {
        AlertEventEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_NOT_FOUND, "告警事件不存在: " + id);
        }
        return entity;
    }

    /**
     * 人工确认事件（自动认领）：FIRING / SILENCED → ACKNOWLEDGED，记录认领人与时间（规格 2.3）
     */
    public AlertEventEntity acknowledge(Long id) {
        AlertEventEntity entity = getById(id);
        if (AlertEventStatus.RESOLVED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_NOT_FOUND, "已恢复事件无需确认: " + id);
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(AlertEventStatus.ACKNOWLEDGED.name());
        entity.setAcknowledgedBy(SecurityUtils.getCurrentUsername());
        entity.setAcknowledgedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return entity;
    }

    /**
     * 独立认领（规格 3.2）：记录 owner；如为 FIRING/SILENCED 自动转为 ACKNOWLEDGED
     * 接管他人告警时覆盖 owner（前端已二次确认）
     */
    public AlertEventEntity claim(Long id) {
        AlertEventEntity entity = getById(id);
        if (AlertEventStatus.RESOLVED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_NOT_FOUND, "已恢复事件无需认领: " + id);
        }
        LocalDateTime now = LocalDateTime.now();
        if (AlertEventStatus.FIRING.name().equals(entity.getStatus())
                || AlertEventStatus.SILENCED.name().equals(entity.getStatus())) {
            entity.setStatus(AlertEventStatus.ACKNOWLEDGED.name());
        }
        entity.setAcknowledgedBy(SecurityUtils.getCurrentUsername());
        entity.setAcknowledgedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return entity;
    }

    /**
     * 批量确认（跳过不可确认事件，返回成功条数）
     */
    public int batchAcknowledge(List<Long> ids) {
        int count = 0;
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        for (Long id : ids) {
            try {
                acknowledge(id);
                count++;
            } catch (BusinessException ignored) {
                // 已恢复等不可确认事件跳过
            }
        }
        return count;
    }

    /**
     * 批量静默（跳过已恢复等不可静默事件，返回成功条数）
     */
    public int batchSilence(List<Long> ids, String reason, LocalDateTime silencedUntil, String note) {
        int count = 0;
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        for (Long id : ids) {
            try {
                silence(id, reason, silencedUntil, note);
                count++;
            } catch (BusinessException ignored) {
                // 已恢复等不可静默事件跳过
            }
        }
        return count;
    }

    /**
     * 手动恢复：任意非 RESOLVED 状态 → RESOLVED，记录恢复人/时间/备注（规格 2.4）
     */
    public AlertEventEntity resolve(Long id, String resolutionNote) {
        AlertEventEntity entity = getById(id);
        if (AlertEventStatus.RESOLVED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_NOT_FOUND, "事件已恢复: " + id);
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(AlertEventStatus.RESOLVED.name());
        entity.setResolvedAt(now);
        entity.setResolvedBy(SecurityUtils.getCurrentUsername());
        if (resolutionNote != null && resolutionNote.length() > 500) {
            resolutionNote = resolutionNote.substring(0, 500);
        }
        entity.setResolutionNote(resolutionNote);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return entity;
    }

    /**
     * 手动静默：FIRING / SILENCED / ACKNOWLEDGED → SILENCED（规格 2.5）
     * 记录静默前状态（silencedFrom），到期/解除后回原状态
     */
    public AlertEventEntity silence(Long id, String reason, LocalDateTime silencedUntil, String note) {
        AlertEventEntity entity = getById(id);
        if (AlertEventStatus.RESOLVED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_NOT_FOUND, "已恢复事件无需静默: " + id);
        }
        LocalDateTime now = LocalDateTime.now();
        if (silencedUntil == null || silencedUntil.isBefore(now.plusMinutes(MIN_SILENCE_MINUTES))) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_INVALID,
                    "静默到期时间必须晚于当前时间 15 分钟");
        }
        if (silencedUntil.isAfter(now.plusDays(7))) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_INVALID, "静默最多 7 天");
        }
        String currentStatus = entity.getStatus();
        entity.setStatus(AlertEventStatus.SILENCED.name());
        entity.setSilencedReason(reason);
        entity.setSilencedUntil(silencedUntil);
        entity.setSilencedBy(SecurityUtils.getCurrentUsername());
        entity.setSilencedAt(now);
        if (note != null && note.length() > 500) {
            note = note.substring(0, 500);
        }
        entity.setSilencedNote(note);
        if (entity.getSilencedFrom() == null) {
            // 首次静默：记录静默前状态，到期/解除后回原状态
            entity.setSilencedFrom(currentStatus);
        }
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return entity;
    }

    /**
     * 解除静默（规格 2.2）：SILENCED → 静默前状态（默认 FIRING），清除静默字段
     */
    public AlertEventEntity unsilence(Long id) {
        AlertEventEntity entity = getById(id);
        if (!AlertEventStatus.SILENCED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_INVALID, "仅静默状态可解除静默: " + id);
        }
        LocalDateTime now = LocalDateTime.now();
        String from = entity.getSilencedFrom();
        entity.setStatus(from != null && !from.isBlank()
                ? from : AlertEventStatus.FIRING.name());
        entity.setSilencedUntil(null);
        entity.setSilencedReason(null);
        entity.setSilencedBy(null);
        entity.setSilencedNote(null);
        entity.setSilencedFrom(null);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return entity;
    }

    /**
     * 静默到期自动恢复（定时任务调用）：SILENCED 且已到期 → 回原状态（规格 2.1）
     */
    public void restoreExpiredSilences() {
        List<AlertEventEntity> expired = mapper.selectList(new LambdaQueryWrapper<AlertEventEntity>()
                .eq(AlertEventEntity::getStatus, AlertEventStatus.SILENCED.name())
                .lt(AlertEventEntity::getSilencedUntil, LocalDateTime.now())
                .last("LIMIT 200"));
        for (AlertEventEntity entity : expired) {
            try {
                unsilence(entity.getId());
            } catch (BusinessException ex) {
                // 单条失败不阻断后续
            }
        }
    }

    /**
     * 查询某规则当前处于 FIRING 的最新事件（去重用）
     */
    public AlertEventEntity findLatestFiring(Long ruleId) {
        List<AlertEventEntity> rows = mapper.selectList(new LambdaQueryWrapper<AlertEventEntity>()
                .eq(AlertEventEntity::getRuleId, ruleId)
                .eq(AlertEventEntity::getStatus, AlertEventStatus.FIRING.name())
                .orderByDesc(AlertEventEntity::getFiredAt)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 创建事件（评估器使用）
     */
    public AlertEventEntity create(AlertEventEntity event) {
        if (event.getUpdatedAt() == null) {
            event.setUpdatedAt(LocalDateTime.now());
        }
        mapper.insert(event);
        return event;
    }

    /**
     * 更新事件（评估器恢复状态使用）
     */
    public void update(AlertEventEntity event) {
        event.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(event);
    }

    /**
     * 转响应 DTO（当前登录用户时填充"有效已读"：readAt >= firedAt）
     */
    public AlertEventResponse toResponse(AlertEventEntity entity) {
        AlertEventResponse response = AlertEventResponse.builder().build();
        BeanUtils.copyProperties(entity, response);
        String username = SecurityUtils.getCurrentUsername();
        if (username != null) {
            Map<Long, LocalDateTime> firedMap = new HashMap<>();
            firedMap.put(entity.getId(), entity.getFiredAt());
            response.setRead(readService.readIds(username, List.of(entity.getId()), firedMap)
                    .contains(entity.getId()));
        }
        return response;
    }
}
