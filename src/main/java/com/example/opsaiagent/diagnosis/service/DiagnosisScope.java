package com.example.opsaiagent.diagnosis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.rbac.service.DeptService;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.example.opsaiagent.security.util.SecurityUtils;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 快照 / 诊断查询数据范围。
 *
 * 企业管理员看本企业；部门用户看本部门子树；系统诊断按快照归属构建检索范围。
 */
@Component
@RequiredArgsConstructor
public class DiagnosisScope {

    private final DataScope dataScope;
    private final DeptService deptService;

    /**
     * 当前请求范围。
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
        Set<Long> deptIds = null;
        if (dataScope.deptId() != null) {
            deptIds = new LinkedHashSet<>(dataScope.visibleDeptIds());
        }
        return of(tenantId, deptIds);
    }

    /**
     * 按快照归属构建范围。
     *
     * @param snapshot 快照
     * @return 范围；无租户时保持全量兼容
     */
    public Scope of(FailureSnapshotEntity snapshot) {
        if (snapshot == null || !StringUtils.hasText(snapshot.getTenantId())) {
            return unrestricted();
        }
        Set<Long> deptIds = null;
        if (snapshot.getDeptId() != null) {
            deptIds = new LinkedHashSet<>(deptService.subtreeIds(
                    snapshot.getTenantId(), snapshot.getDeptId()));
        }
        return of(snapshot.getTenantId(), deptIds);
    }

    public Scope of(String tenantId, Set<Long> deptIds) {
        if (!StringUtils.hasText(tenantId)) {
            return unrestricted();
        }
        return new Scope(tenantId, deptIds, Mode.SCOPED);
    }

    public Scope unrestricted() {
        return new Scope(null, null, Mode.UNRESTRICTED);
    }

    /** 范围模式 */
    public enum Mode {
        UNRESTRICTED,
        SCOPED
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
            this.deptIds = deptIds;
            this.mode = mode;
        }

        public boolean isUnrestricted() {
            return mode == Mode.UNRESTRICTED;
        }

        public LambdaQueryWrapper<FailureSnapshotEntity> snapshots() {
            LambdaQueryWrapper<FailureSnapshotEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            wrapper.eq(FailureSnapshotEntity::getTenantId, tenantId);
            if (deptIds != null) {
                if (deptIds.isEmpty()) {
                    wrapper.eq(FailureSnapshotEntity::getDeptId, -1L);
                } else {
                    wrapper.in(FailureSnapshotEntity::getDeptId, deptIds);
                }
            }
            return wrapper;
        }

        public LambdaQueryWrapper<DiagnosisRecordEntity> diagnoses() {
            LambdaQueryWrapper<DiagnosisRecordEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            wrapper.eq(DiagnosisRecordEntity::getTenantId, tenantId);
            if (deptIds != null) {
                if (deptIds.isEmpty()) {
                    wrapper.eq(DiagnosisRecordEntity::getDeptId, -1L);
                } else {
                    wrapper.in(DiagnosisRecordEntity::getDeptId, deptIds);
                }
            }
            return wrapper;
        }

        public boolean canAccess(FailureSnapshotEntity snapshot) {
            if (isUnrestricted()) {
                return true;
            }
            if (snapshot == null || !tenantId.equals(snapshot.getTenantId())) {
                return false;
            }
            return deptIds == null || (snapshot.getDeptId() != null && deptIds.contains(snapshot.getDeptId()));
        }

        public boolean canAccess(DiagnosisRecordEntity record) {
            if (isUnrestricted()) {
                return true;
            }
            if (record == null || !tenantId.equals(record.getTenantId())) {
                return false;
            }
            return deptIds == null || (record.getDeptId() != null && deptIds.contains(record.getDeptId()));
        }

        /**
         * 诊断检索范围；不受限时返回 null，调用方使用默认检索。
         *
         * @return 检索范围
         */
        public RetrievalScope retrievalScope() {
            if (isUnrestricted()) {
                return null;
            }
            return new RetrievalScope(tenantId, deptIds, SecurityUtils.getCurrentUsername());
        }
    }
}
