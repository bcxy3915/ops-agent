package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertEventResponse;
import com.example.opsaiagent.alert.dto.AlertEventStatus;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警事件服务（查询 / 确认 / 状态流转）
 */
@Service
@RequiredArgsConstructor
public class AlertEventService {

    private final AlertEventMapper mapper;

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
     * 人工确认事件：FIRING / SILENCED → ACKNOWLEDGED
     */
    public AlertEventEntity acknowledge(Long id) {
        AlertEventEntity entity = getById(id);
        if (AlertEventStatus.RESOLVED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_NOT_FOUND, "已恢复事件无需确认: " + id);
        }
        entity.setStatus(AlertEventStatus.ACKNOWLEDGED.name());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return entity;
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
     * 转响应 DTO
     */
    public AlertEventResponse toResponse(AlertEventEntity entity) {
        AlertEventResponse response = AlertEventResponse.builder().build();
        BeanUtils.copyProperties(entity, response);
        return response;
    }
}
