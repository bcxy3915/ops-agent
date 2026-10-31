package com.example.opsaiagent.retrieval;

import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.security.util.SecurityUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * RetrievalScope 单元测试。
 */
@DisplayName("检索数据范围")
class RetrievalScopeTest {

    @Test
    @DisplayName("current：无部门普通用户不可见部门知识")
    void current_noDeptOrdinaryUser() {
        DataScope dataScope = mock(DataScope.class);
        when(dataScope.tenantId()).thenReturn("t1");
        when(dataScope.isPlatformTenant()).thenReturn(false);
        when(dataScope.deptId()).thenReturn(null);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("u1");
            sec.when(() -> SecurityUtils.hasRole("TENANT_ADMIN")).thenReturn(false);
            sec.when(() -> SecurityUtils.hasPermission("knowledge:admin")).thenReturn(false);

            RetrievalScope scope = RetrievalScope.current(dataScope);
            assertThat(scope.deptIds()).isEmpty();
            assertThat(scope.matches(Map.of(
                    "visibility", "DEPT", "tenantId", "t1", "deptId", "5",
                    "reviewStatus", "APPROVED"))).isFalse();
        }
    }

    @Test
    @DisplayName("current：租户管理员无部门时可检索全部部门")
    void current_noDeptTenantAdmin() {
        DataScope dataScope = mock(DataScope.class);
        when(dataScope.tenantId()).thenReturn("t1");
        when(dataScope.isPlatformTenant()).thenReturn(false);
        when(dataScope.deptId()).thenReturn(null);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            sec.when(() -> SecurityUtils.hasRole("TENANT_ADMIN")).thenReturn(true);

            RetrievalScope scope = RetrievalScope.current(dataScope);
            assertThat(scope.deptIds()).isNull();
            assertThat(scope.matches(Map.of(
                    "visibility", "DEPT", "tenantId", "t1", "deptId", "5",
                    "reviewStatus", "APPROVED"))).isTrue();
        }
    }

    @Test
    @DisplayName("matches：PUBLIC 对所有租户可见")
    void matches_public() {
        RetrievalScope scope = new RetrievalScope("t1", Set.of(5L), "u1");

        assertThat(scope.matches(Map.of("visibility", "PUBLIC"))).isTrue();
    }

    @Test
    @DisplayName("matches：TENANT 仅同租户且已审核")
    void matches_tenant() {
        RetrievalScope scope = new RetrievalScope("t1", Set.of(5L), "u1");

        assertThat(scope.matches(Map.of(
                "visibility", "TENANT", "tenantId", "t1", "reviewStatus", "APPROVED"))).isTrue();
        assertThat(scope.matches(Map.of(
                "visibility", "TENANT", "tenantId", "t2", "reviewStatus", "APPROVED"))).isFalse();
        assertThat(scope.matches(Map.of(
                "visibility", "TENANT", "tenantId", "t1", "reviewStatus", "PENDING_DEPT"))).isFalse();
    }

    @Test
    @DisplayName("matches：DEPT 仅部门子树内可见")
    void matches_dept() {
        RetrievalScope scope = new RetrievalScope("t1", Set.of(5L, 6L), "u1");

        assertThat(scope.matches(Map.of(
                "visibility", "DEPT", "tenantId", "t1", "deptId", "5",
                "reviewStatus", "APPROVED"))).isTrue();
        assertThat(scope.matches(Map.of(
                "visibility", "DEPT", "tenantId", "t1", "deptId", "9",
                "reviewStatus", "APPROVED"))).isFalse();
    }

    @Test
    @DisplayName("matches：PERSONAL 仅作者本人可见")
    void matches_personal() {
        RetrievalScope scope = new RetrievalScope("t1", Set.of(5L), "u1");

        assertThat(scope.matches(Map.of(
                "visibility", "PERSONAL", "tenantId", "t1", "owner", "u1"))).isTrue();
        assertThat(scope.matches(Map.of(
                "visibility", "PERSONAL", "tenantId", "t1", "owner", "u2"))).isFalse();
        // 用户名租户内唯一，跨租户同名不得互相可见
        assertThat(scope.matches(Map.of(
                "visibility", "PERSONAL", "tenantId", "t2", "owner", "u1"))).isFalse();
    }

    @Test
    @DisplayName("filterExpression：包含 PUBLIC/TENANT/DEPT/PERSONAL 分支")
    void filterExpression() {
        RetrievalScope scope = new RetrievalScope("t1", Set.of(5L), "u1");

        String expression = scope.filterExpression();

        assertThat(expression)
                .contains("visibility == 'PUBLIC'")
                .contains("visibility == 'TENANT'")
                .contains("tenantId == 't1'")
                .contains("visibility == 'DEPT'")
                .contains("deptId == '5'")
                .contains("visibility == 'PERSONAL'")
                .contains("owner == 'u1'")
                .contains("reviewStatus == 'APPROVED'");
    }

    @Test
    @DisplayName("filterExpression：每个分支都必须限制 reviewStatus=APPROVED")
    void filterExpression_requiresApproved() {
        RetrievalScope scope = new RetrievalScope("t1", Set.of(5L), "u1");

        String expression = scope.filterExpression();

        // 4 个 visibility 分支各自带审核通过条件，草稿/待审核内容不可命中
        assertThat(expression.split("reviewStatus == 'APPROVED'", -1)).hasSize(5);
    }

    @Test
    @DisplayName("filterExpression：无租户时仅 PUBLIC 且已审核")
    void filterExpression_publicOnly() {
        assertThat(RetrievalScope.publicOnly().filterExpression())
                .isEqualTo("visibility == 'PUBLIC' && reviewStatus == 'APPROVED'");
    }
}
