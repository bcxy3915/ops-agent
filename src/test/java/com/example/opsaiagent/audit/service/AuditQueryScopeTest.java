package com.example.opsaiagent.audit.service;

import com.example.opsaiagent.audit.entity.AuditLogEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditQueryScope 单元测试。
 */
@DisplayName("审计查询范围")
class AuditQueryScopeTest {

    @Test
    @DisplayName("无上下文：不受限")
    void current_unrestricted() {
        assertThat(new AuditQueryScope(null).current().isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("租户范围：仅本租户日志可见")
    void tenantScope_canAccess() {
        AuditQueryScope.Scope scope = new AuditQueryScope.Scope("t1");

        AuditLogEntity sameTenant = new AuditLogEntity();
        sameTenant.setTenantId("t1");
        AuditLogEntity otherTenant = new AuditLogEntity();
        otherTenant.setTenantId("t2");

        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.canAccess(sameTenant)).isTrue();
        assertThat(scope.canAccess(otherTenant)).isFalse();
    }
}
