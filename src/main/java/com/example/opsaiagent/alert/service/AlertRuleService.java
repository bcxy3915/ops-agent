package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertRuleLevelDto;
import com.example.opsaiagent.alert.dto.AlertRuleRequest;
import com.example.opsaiagent.alert.dto.AlertRuleResponse;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.entity.AlertRuleEscalationLevelEntity;
import com.example.opsaiagent.alert.mapper.AlertRuleEscalationLevelMapper;
import com.example.opsaiagent.alert.mapper.AlertRuleMapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 告警规则 CRUD 服务
 * P0 升级策略（规格 4.1）：多级升级路径 alert_rule_escalation_level
 */
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private static final List<String> OPERATORS = List.of("gt", "lt");
    private static final List<String> COMPARE_MODES = List.of("THRESHOLD", "YOY", "SIGMA");
    private static final List<String> TRIGGER_ONS = List.of("UNACKNOWLEDGED", "UNRESOLVED");
    private static final List<String> ROLES = List.of("OPERATOR", "ADMIN");

    private final AlertRuleMapper mapper;
    private final AlertRuleEscalationLevelMapper levelMapper;

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
     * 创建规则（含升级路径）
     */
    @Transactional
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
        replaceEscalationLevels(entity.getId(), request.getEscalationLevels());
        return entity;
    }

    /**
     * 更新规则（含升级路径）
     */
    @Transactional
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
        replaceEscalationLevels(id, request.getEscalationLevels());
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
     * 删除规则（连带升级路径）
     */
    @Transactional
    public void delete(Long id) {
        if (mapper.deleteById(id) == 0) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
        levelMapper.delete(new LambdaQueryWrapper<AlertRuleEscalationLevelEntity>()
                .eq(AlertRuleEscalationLevelEntity::getRuleId, id));
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
     * 查询规则的升级路径（按级别升序）
     */
    public List<AlertRuleEscalationLevelEntity> listLevels(Long ruleId) {
        return levelMapper.selectList(new LambdaQueryWrapper<AlertRuleEscalationLevelEntity>()
                .eq(AlertRuleEscalationLevelEntity::getRuleId, ruleId)
                .orderByAsc(AlertRuleEscalationLevelEntity::getLevelNo));
    }

    /**
     * 转响应 DTO（含升级路径）
     */
    public AlertRuleResponse toResponse(AlertRuleEntity entity) {
        AlertRuleResponse response = AlertRuleResponse.builder().build();
        BeanUtils.copyProperties(entity, response);
        response.setEscalationLevels(listLevels(entity.getId()).stream()
                .map(this::toLevelDto)
                .collect(Collectors.toList()));
        return response;
    }

    private AlertRuleLevelDto toLevelDto(AlertRuleEscalationLevelEntity entity) {
        AlertRuleLevelDto dto = new AlertRuleLevelDto();
        dto.setAfterSeconds(entity.getAfterSeconds());
        dto.setTriggerOn(entity.getTriggerOn());
        dto.setToRole(entity.getToRole());
        dto.setToUsers(splitList(entity.getToUsers()));
        dto.setChannelIds(splitList(entity.getChannelIds()));
        return dto;
    }

    /**
     * 全量替换规则升级路径（先删后插）
     */
    private void replaceEscalationLevels(Long ruleId, List<AlertRuleLevelDto> levels) {
        levelMapper.delete(new LambdaQueryWrapper<AlertRuleEscalationLevelEntity>()
                .eq(AlertRuleEscalationLevelEntity::getRuleId, ruleId));
        if (levels == null || levels.isEmpty()) {
            return;
        }
        int no = 1;
        for (AlertRuleLevelDto dto : levels) {
            AlertRuleEscalationLevelEntity entity = new AlertRuleEscalationLevelEntity();
            entity.setRuleId(ruleId);
            entity.setLevelNo(no++);
            entity.setAfterSeconds(dto.getAfterSeconds());
            entity.setTriggerOn(dto.getTriggerOn() == null ? "UNACKNOWLEDGED" : dto.getTriggerOn());
            entity.setToRole(dto.getToRole());
            entity.setToUsers(joinList(dto.getToUsers()));
            entity.setChannelIds(joinList(dto.getChannelIds()));
            entity.setCreatedAt(LocalDateTime.now());
            levelMapper.insert(entity);
        }
    }

    private String joinList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return String.join(",", list.stream()
                .filter(s -> s != null && !s.isBlank())
                .toList());
    }

    private List<String> splitList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String s : raw.split(",")) {
            if (!s.isBlank()) {
                result.add(s.trim());
            }
        }
        return result;
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
        validateEscalation(request);
    }

    /**
     * 升级路径校验（规格 4.1）：启用时至少 1 级、after 1m~24h 且严格递增、toRole/toUsers 至少一个
     */
    private void validateEscalation(AlertRuleRequest request) {
        Boolean enabled = request.getEscalationEnabled();
        List<AlertRuleLevelDto> levels = request.getEscalationLevels();
        if (enabled == null || !enabled || levels == null || levels.isEmpty()) {
            return;
        }
        if (levels.size() > 10) {
            throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "升级级别最多 10 级");
        }
        int prev = 0;
        for (AlertRuleLevelDto level : levels) {
            int after = level.getAfterSeconds() == null ? 0 : level.getAfterSeconds();
            if (after < 60 || after > 86400) {
                throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "升级 after 需在 1 分钟 ~ 24 小时之间");
            }
            if (after <= prev) {
                throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "升级级别 after 必须严格递增");
            }
            prev = after;
            String triggerOn = level.getTriggerOn() == null ? "UNACKNOWLEDGED" : level.getTriggerOn();
            if (!TRIGGER_ONS.contains(triggerOn)) {
                throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "triggerOn 仅支持 UNACKNOWLEDGED / UNRESOLVED");
            }
            if (level.getToRole() != null && !ROLES.contains(level.getToRole())) {
                throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "toRole 仅支持 OPERATOR / ADMIN");
            }
            boolean hasRole = level.getToRole() != null && !level.getToRole().isBlank();
            boolean hasUsers = level.getToUsers() != null && !level.getToUsers().isEmpty();
            if (!hasRole && !hasUsers) {
                throw new BusinessException(ErrorCode.ALERT_RULE_INVALID, "每级升级需指定角色或用户");
            }
        }
    }
}
