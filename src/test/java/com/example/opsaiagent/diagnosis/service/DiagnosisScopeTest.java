package com.example.opsaiagent.diagnosis.service;

import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DiagnosisScope 单元测试。
 */
@DisplayName("诊断数据范围")
class DiagnosisScopeTest {

    @Test
    @DisplayName("无上下文：不受限")
    void current_unrestricted() {
        assertThat(new DiagnosisScope(null, null).current().isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("按租户/部门范围判断快照与诊断可见性")
    void scoped_canAccess() {
        DiagnosisScope.Scope scope = new DiagnosisScope(null, null).of("t1", Set.of(5L));

        FailureSnapshotEntity snapshot = new FailureSnapshotEntity();
        snapshot.setTenantId("t1");
        snapshot.setDeptId(5L);
        DiagnosisRecordEntity record = new DiagnosisRecordEntity();
        record.setTenantId("t1");
        record.setDeptId(5L);

        assertThat(scope.canAccess(snapshot)).isTrue();
        assertThat(scope.canAccess(record)).isTrue();
        assertThat(scope.retrievalScope()).isNotNull();

        snapshot.setDeptId(9L);
        record.setTenantId("t2");
        assertThat(scope.canAccess(snapshot)).isFalse();
        assertThat(scope.canAccess(record)).isFalse();
    }
}
