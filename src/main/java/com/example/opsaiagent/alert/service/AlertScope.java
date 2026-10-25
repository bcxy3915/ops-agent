package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 告警查询数据范围。
 *
 * 企业管理员看本企业；部门用户看本部门子树；无租户上下文保持全量兼容。
 */
@Component
@RequiredArgsConstructor
public class AlertScope {

    private final DataScope dataScope;

    /**
     * 当前查询范围。
     *
     * @return 范围
     */
    public Scope current() {
        if (dataScope == null) {
            return unrestricted();
        }
        String tenantId = dataScope.tenantId();
        if (!StringUtils.hasText(tenantId) || dataScope.isPlatformTenant()) {
            return unrestricted();
        }
        if (SecurityUtils.hasPermission("alert:manage")) {
            return tenant(tenantId);
        }
        Set<Long> deptIds = Set.of();
        if (dataScope.deptId() != null) {
            deptIds = new LinkedHashSet<>(dataScope.visibleDeptIds());
        }
        return user(tenantId, deptIds);
    }

    public Scope unrestricted() {
        return new Scope(null, Set.of(), Mode.UNRESTRICTED);
    }

    public Scope tenant(String tenantId) {
        return new Scope(tenantId, Set.of(), Mode.TENANT);
    }

    public Scope user(String tenantId, Set<Long> deptIds) {
        return new Scope(tenantId, deptIds == null ? Set.of() : deptIds, Mode.USER);
    }

    /** 范围模式 */
    public enum Mode {
        UNRESTRICTED,
        TENANT,
        USER
    }

    /**
     * 具体查询范围。
     */
    public static class Scope {

        private final String tenantId;
        private final Set<Long> deptIds;
        private final Mode mode;

        Scope(String tenantId, Set<Long> deptIds, Mode mode) {
            this.tenantId = tenantId;
            this.deptIds = deptIds == null ? Set.of() : deptIds;
            this.mode = mode;
        }

        public boolean isUnrestricted() {
            return mode == Mode.UNRESTRICTED;
        }

        public boolean isTenantAdmin() {
            return mode == Mode.TENANT;
        }

        public LambdaQueryWrapper<AlertRuleEntity> rules() {
            LambdaQueryWrapper<AlertRuleEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            wrapper.eq(AlertRuleEntity::getTenantId, tenantId);
            if (!isTenantAdmin()) {
                if (deptIds.isEmpty()) {
                    wrapper.eq(AlertRuleEntity::getDeptId, -1L);
                } else {
                    wrapper.in(AlertRuleEntity::getDeptId, deptIds);
                }
            }
            return wrapper;
        }

        public LambdaQueryWrapper<AlertEventEntity> events() {
            LambdaQueryWrapper<AlertEventEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            wrapper.eq(AlertEventEntity::getTenantId, tenantId);
            if (!isTenantAdmin()) {
                if (deptIds.isEmpty()) {
                    wrapper.eq(AlertEventEntity::getDeptId, -1L);
                } else {
                    wrapper.in(AlertEventEntity::getDeptId, deptIds);
                }
            }
            return wrapper;
        }

        public boolean canAccess(AlertRuleEntity rule) {
            if (isUnrestricted()) {
                return true;
            }
            if (rule == null || !tenantId.equals(rule.getTenantId())) {
                return false;
            }
            return isTenantAdmin() || (rule.getDeptId() != null && deptIds.contains(rule.getDeptId()));
        }

        public boolean canAccess(AlertEventEntity event) {
            if (isUnrestricted()) {
                return true;
            }
            if (event == null || !tenantId.equals(event.getTenantId())) {
                return false;
            }
            return isTenantAdmin() || (event.getDeptId() != null && deptIds.contains(event.getDeptId()));
        }
    }
}
