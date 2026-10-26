package com.example.opsaiagent.alert.service;

import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertScope 单元测试。
 */
@DisplayName("告警数据范围")
class AlertScopeTest {

    @Test
    @DisplayName("无上下文：不受限")
    void current_unrestricted() {
        assertThat(new AlertScope(null).current().isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("企业管理员：本企业规则/事件可见")
    void tenantAdmin_canAccess() {
        AlertScope.Scope scope = new AlertScope(null).tenant("t1");

        assertThat(scope.canAccess(rule("t1", 5L))).isTrue();
        assertThat(scope.canAccess(rule("t2", 5L))).isFalse();
        assertThat(scope.canAccess(event("t1", 5L))).isTrue();
        assertThat(scope.canAccess(event("t2", 5L))).isFalse();
    }

    @Test
    @DisplayName("部门用户：仅本部门子树可见")
    void deptUser_canAccess() {
        AlertScope.Scope scope = new AlertScope(null).user("t1", Set.of(5L, 6L));

        assertThat(scope.canAccess(rule("t1", 5L))).isTrue();
        assertThat(scope.canAccess(rule("t1", 9L))).isFalse();
        assertThat(scope.canAccess(event("t1", 6L))).isTrue();
        assertThat(scope.canAccess(event("t1", 9L))).isFalse();
    }

    private AlertRuleEntity rule(String tenantId, Long deptId) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setTenantId(tenantId);
        rule.setDeptId(deptId);
        return rule;
    }

    private AlertEventEntity event(String tenantId, Long deptId) {
        AlertEventEntity event = new AlertEventEntity();
        event.setTenantId(tenantId);
        event.setDeptId(deptId);
        return event;
    }
}
