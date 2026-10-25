package com.example.opsaiagent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetrievalScope 单元测试。
 */
@DisplayName("检索数据范围")
class RetrievalScopeTest {

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
                .contains("owner == 'u1'");
    }

    @Test
    @DisplayName("filterExpression：无租户时仅 PUBLIC")
    void filterExpression_publicOnly() {
        assertThat(RetrievalScope.publicOnly().filterExpression())
                .isEqualTo("visibility == 'PUBLIC'");
    }
}
