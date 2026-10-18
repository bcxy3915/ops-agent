package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertRuleRequest;
import com.example.opsaiagent.alert.dto.AlertRuleResponse;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.mapper.AlertRuleMapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则 CRUD 服务
 */
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private static final List<String> OPERATORS = List.of("gt", "lt");
    private static final List<String> COMPARE_MODES = List.of("THRESHOLD", "YOY", "SIGMA");

    private final AlertRuleMapper mapper;

    /**
     * 查询规则列表（可按服务 / 启用状态过滤）
     */
    public List<AlertRuleEntity> list(String service, Boolean enabled) {
        LambdaQueryWrapper<AlertRuleEntity> qw = new LambdaQueryWrapper<>();
        if (service != null && !service.isBlank()) {
            qw.eq(AlertRuleEntity::getService, service);
        }
        if (enabled != null) {
            qw.eq(AlertRuleEntity::getEnabled, enabled);
        }
        qw.orderByDesc(AlertRuleEntity::getCreatedAt);
        return mapper.selectList(qw);
    }

    /**
     * 创建规则
     */
    public AlertRuleEntity create(AlertRuleRequest request) {
        validate(request);
        if (nameExists(request.getName(), null)) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NAME_EXISTS,
                    "告警规则名称已存在: " + request.getName());
        }
        AlertRuleEntity entity = new AlertRuleEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.insert(entity);
        return entity;
    }

    /**
     * 更新规则
     */
    public AlertRuleEntity update(Long id, AlertRuleRequest request) {
        AlertRuleEntity existing = getById(id);
        validate(request);
        if (request.getName() != null && !request.getName().equals(existing.getName())
                && nameExists(request.getName(), id)) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NAME_EXISTS,
                    "告警规则名称已存在: " + request.getName());
        }
        BeanUtils.copyProperties(request, existing);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        return existing;
    }

    /**
     * 启用 / 停用规则
     */
    public AlertRuleEntity setEnabled(Long id, boolean enabled) {
        AlertRuleEntity existing = getById(id);
        existing.setEnabled(enabled);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        return existing;
    }

    /**
     * 删除规则
     */
    public void delete(Long id) {
        if (mapper.deleteById(id) == 0) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
    }

    /**
     * 按 ID 查询
     */
    public AlertRuleEntity getById(Long id) {
        AlertRuleEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
        return entity;
    }

    /**
     * 转响应 DTO
     */
    public AlertRuleResponse toResponse(AlertRuleEntity entity) {
        AlertRuleResponse response = AlertRuleResponse.builder().build();
        BeanUtils.copyProperties(entity, response);
        return response;
    }

    private boolean nameExists(String name, Long excludeId) {
        LambdaQueryWrapper<AlertRuleEntity> qw = new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getName, name);
        if (excludeId != null) {
            qw.ne(AlertRuleEntity::getId, excludeId);
        }
        return mapper.selectCount(qw) > 0;
    }

    private void validate(AlertRuleRequest request) {
        if (request.getOperator() == null || !OPERATORS.contains(request.getOperator())) {
            throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "比较符仅支持 gt / lt");
        }
        String mode = request.getCompareMode() == null ? "THRESHOLD" : request.getCompareMode();
        if (!COMPARE_MODES.contains(mode)) {
            throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "比较模式仅支持 THRESHOLD / YOY / SIGMA");
        }
        if (request.getThreshold() == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "阈值不能为空");
        }
        if (request.getDurationSeconds() == null || request.getDurationSeconds() < 0) {
            throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "持续时长不能小于 0");
        }
        if (request.getSilenceSeconds() == null || request.getSilenceSeconds() < 0) {
            throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "静默期不能小于 0");
        }
    }
}
