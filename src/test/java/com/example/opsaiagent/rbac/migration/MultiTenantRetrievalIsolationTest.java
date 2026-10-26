package com.example.opsaiagent.rbac.migration;

import com.example.opsaiagent.retrieval.RetrievalScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户检索隔离集成校验。
 *
 * 覆盖 PUBLIC / TENANT / DEPT / PERSONAL 四种可见性在跨租户、跨部门下的匹配行为。
 */
@DisplayName("多租户检索隔离")
class MultiTenantRetrievalIsolationTest {

    @Test
    @DisplayName("公开知识对所有范围可见")
    void publicVisibleToAllScopes() {
        Map<String, Object> md = metadata("PUBLIC", "t-any", "1", "APPROVED", "alice");
        assertThat(RetrievalScope.publicOnly().matches(md)).isTrue();
        assertThat(scope("t1", Set.of(5L), "bob").matches(md)).isTrue();
    }

    @Test
    @DisplayName("企业知识仅同租户可见")
    void tenantKnowledgeScopedByTenant() {
        Map<String, Object> md = metadata("TENANT", "t1", null, "APPROVED", "alice");
        assertThat(scope("t1", null, "bob").matches(md)).isTrue();
        assertThat(scope("t2", null, "bob").matches(md)).isFalse();
        assertThat(scope("t1", null, "bob").matches(
                metadata("TENANT", "t1", null, "DRAFT", "alice"))).isFalse();
    }

    @Test
    @DisplayName("部门知识仅可见部门子树可见")
    void deptKnowledgeScopedByDept() {
        Map<String, Object> md = metadata("DEPT", "t1", "5", "APPROVED", "alice");
        assertThat(scope("t1", Set.of(5L, 6L), "bob").matches(md)).isTrue();
        assertThat(scope("t1", Set.of(9L), "bob").matches(md)).isFalse();
        assertThat(scope("t1", null, "bob").matches(md)).isTrue();
    }

    @Test
    @DisplayName("个人知识仅 owner 本人可见")
    void personalKnowledgeOwnerOnly() {
        Map<String, Object> md = metadata("PERSONAL", "t1", "5", "APPROVED", "alice");
        assertThat(scope("t1", Set.of(5L), "alice").matches(md)).isTrue();
        assertThat(scope("t1", Set.of(5L), "bob").matches(md)).isFalse();
    }

    @Test
    @DisplayName("filterExpression 限定当前租户")
    void filterExpressionBindsTenant() {
        String expr = scope("t1", Set.of(5L), "bob").filterExpression();
        assertThat(expr).contains("tenantId == 't1'");
        assertThat(expr).doesNotContain("tenantId == 't2'");
    }

    private RetrievalScope scope(String tenantId, Set<Long> deptIds, String userId) {
        return new RetrievalScope(tenantId, deptIds, userId);
    }

    private Map<String, Object> metadata(String visibility, String tenantId, String deptId,
                                         String reviewStatus, String owner) {
        Map<String, Object> md = new java.util.HashMap<>();
        md.put("visibility", visibility);
        md.put("tenantId", tenantId);
        md.put("deptId", deptId);
        md.put("reviewStatus", reviewStatus);
        md.put("owner", owner);
        return md;
    }
}
