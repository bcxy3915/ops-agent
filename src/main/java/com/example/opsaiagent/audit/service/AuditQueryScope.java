package com.example.opsaiagent.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.rbac.service.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 审计日志查询范围。
 *
 * 企业管理员看本企业；平台租户/无上下文保持全量兼容。
 */
@Component
@RequiredArgsConstructor
public class AuditQueryScope {

    private final DataScope dataScope;

    /**
     * 当前查询范围。
     *
     * @return 范围
     */
    public Scope current() {
        if (dataScope == null) {
            return new Scope(null);
        }
        String tenantId = dataScope.tenantId();
        if (!StringUtils.hasText(tenantId) || dataScope.isPlatformTenant()) {
            return new Scope(null);
        }
        return new Scope(tenantId);
    }

    /**
     * 审计查询范围。
     */
    public static class Scope {

        private final String tenantId;

        Scope(String tenantId) {
            this.tenantId = tenantId;
        }

        public boolean isUnrestricted() {
            return !StringUtils.hasText(tenantId);
        }

        public LambdaQueryWrapper<AuditLogEntity> logs() {
            LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
            if (!isUnrestricted()) {
                wrapper.eq(AuditLogEntity::getTenantId, tenantId);
            }
            return wrapper;
        }

        public boolean canAccess(AuditLogEntity entity) {
            return isUnrestricted() || (entity != null && tenantId.equals(entity.getTenantId()));
        }
    }
}
