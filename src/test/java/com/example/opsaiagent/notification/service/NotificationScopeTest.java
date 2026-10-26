package com.example.opsaiagent.notification.service;

import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationScope 单元测试。
 */
@DisplayName("通知数据范围")
class NotificationScopeTest {

    @Test
    @DisplayName("无上下文：不受限")
    void current_unrestricted() {
        assertThat(new NotificationScope(null).current().isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("企业范围：仅本企业渠道可见")
    void tenantScope_canAccess() {
        NotificationScope.Scope scope = new NotificationScope.Scope("t1");

        NotificationChannelConfigEntity sameTenant = new NotificationChannelConfigEntity();
        sameTenant.setTenantId("t1");
        NotificationChannelConfigEntity otherTenant = new NotificationChannelConfigEntity();
        otherTenant.setTenantId("t2");

        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.canAccess(sameTenant)).isTrue();
        assertThat(scope.canAccess(otherTenant)).isFalse();
    }
}
