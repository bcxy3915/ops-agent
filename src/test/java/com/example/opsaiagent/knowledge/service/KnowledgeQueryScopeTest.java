package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.knowledge.entity.KnowledgeConflictEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeQueryScope 单元测试。
 */
@DisplayName("知识查询数据范围")
class KnowledgeQueryScopeTest {

    @Test
    @DisplayName("无上下文：不受限")
    void current_noContext() {
        assertThat(new KnowledgeQueryScope(null).current().isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("企业管理员：可访问本企业全部状态文档")
    void tenantAdmin_canAccessAllTenantDocs() {
        KnowledgeQueryScope.Scope scope = new KnowledgeQueryScope(null).tenant("t1");

        assertThat(scope.canAccess(doc("t1", "DEPT", "DRAFT", 5L, "u1"))).isTrue();
        assertThat(scope.canAccess(doc("t2", "TENANT", "APPROVED", null, "u1"))).isFalse();
        assertThat(scope.canAccess(doc("0", "PUBLIC", "APPROVED", null, "system"))).isTrue();
    }

    @Test
    @DisplayName("部门用户：本部门子树的部门知识可见")
    void deptUser_visibleDeptSubtree() {
        KnowledgeQueryScope.Scope scope = new KnowledgeQueryScope(null)
                .user("t1", Set.of(5L, 6L), "u1");

        assertThat(scope.canAccess(doc("t1", "DEPT", "APPROVED", 5L, "u2"))).isTrue();
        assertThat(scope.canAccess(doc("t1", "DEPT", "APPROVED", 9L, "u2"))).isFalse();
        assertThat(scope.canAccess(doc("t1", "DEPT", "PENDING_DEPT", 5L, "u2"))).isFalse();
    }

    @Test
    @DisplayName("普通用户：企业知识可见，个人知识仅本人可见")
    void user_tenantAndPersonal() {
        KnowledgeQueryScope.Scope scope = new KnowledgeQueryScope(null)
                .user("t1", Set.of(5L), "u1");

        assertThat(scope.canAccess(doc("t1", "TENANT", "APPROVED", null, "u2"))).isTrue();
        assertThat(scope.canAccess(doc("t1", "PERSONAL", "DRAFT", null, "u1"))).isTrue();
        assertThat(scope.canAccess(doc("t1", "PERSONAL", "DRAFT", null, "u2"))).isFalse();
    }

    @Test
    @DisplayName("冲突：企业管理员看本企业，部门用户看部门子树")
    void conflict_scope() {
        KnowledgeConflictEntity conflict = new KnowledgeConflictEntity();
        conflict.setTenantId("t1");
        conflict.setDeptId(5L);

        assertThat(new KnowledgeQueryScope(null).tenant("t1").canAccess(conflict)).isTrue();
        assertThat(new KnowledgeQueryScope(null).user("t1", Set.of(5L), "u1").canAccess(conflict)).isTrue();
        assertThat(new KnowledgeQueryScope(null).user("t1", Set.of(6L), "u1").canAccess(conflict)).isFalse();
    }

    private KnowledgeDocumentEntity doc(String tenantId, String visibility,
                                        String reviewStatus, Long deptId, String owner) {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setTenantId(tenantId);
        doc.setVisibility(visibility);
        doc.setReviewStatus(reviewStatus);
        doc.setDeptId(deptId);
        doc.setOwner(owner);
        doc.setLayer("PRIVATE");
        return doc;
    }
}
