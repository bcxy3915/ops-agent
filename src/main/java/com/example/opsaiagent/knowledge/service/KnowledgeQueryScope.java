package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeConflictEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeHitLogEntity;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 知识库查询数据范围。
 *
 * 与检索范围保持同一口径：租户管理员看本企业，部门用户看本部门子树 + 企业知识 + 个人知识，
 * 平台/无上下文保持兼容的全量查询。该范围用于统计、命中流水、版本与冲突查询。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeQueryScope {

    private static final String VISIBILITY_PUBLIC = "PUBLIC";
    private static final String VISIBILITY_TENANT = "TENANT";
    private static final String VISIBILITY_DEPT = "DEPT";
    private static final String VISIBILITY_PERSONAL = "PERSONAL";
    private static final String REVIEW_APPROVED = "APPROVED";
    private static final String REVIEW_PENDING_DEPT = "PENDING_DEPT";
    private static final String REVIEW_PENDING_TENANT = "PENDING_TENANT";

    private final DataScope dataScope;

    /**
     * 当前查询范围。
     *
     * @return 范围；无租户上下文或平台租户时为不受限
     */
    public Scope current() {
        if (dataScope == null) {
            return unrestricted();
        }
        String tenantId = dataScope.tenantId();
        if (!StringUtils.hasText(tenantId) || dataScope.isPlatformTenant()) {
            return unrestricted();
        }
        if (SecurityUtils.hasPermission("knowledge:admin")) {
            return tenant(tenantId);
        }
        Set<Long> deptIds = Set.of();
        if (dataScope.deptId() != null) {
            deptIds = new LinkedHashSet<>(dataScope.visibleDeptIds());
        }
        return user(tenantId, deptIds, SecurityUtils.getCurrentUsername());
    }

    /**
     * 不受限范围（系统任务/平台汇总/单测兼容）。
     *
     * @return 范围
     */
    public Scope unrestricted() {
        return new Scope(null, Set.of(), null, Mode.UNRESTRICTED);
    }

    /**
     * 企业管理员范围。
     *
     * @param tenantId 租户 ID
     * @return 范围
     */
    public Scope tenant(String tenantId) {
        return new Scope(tenantId, Set.of(), null, Mode.TENANT);
    }

    /**
     * 部门/普通用户范围。
     *
     * @param tenantId 租户 ID
     * @param deptIds  可见部门
     * @param userId   当前用户
     * @return 范围
     */
    public Scope user(String tenantId, Set<Long> deptIds, String userId) {
        return new Scope(tenantId, deptIds == null ? Set.of() : deptIds, userId, Mode.USER);
    }

    /** 范围模式 */
    public enum Mode {
        UNRESTRICTED,
        TENANT,
        USER
    }

    /**
     * 具体查询范围。
     */
    public static class Scope {

        private final String tenantId;
        private final Set<Long> deptIds;
        private final String userId;
        private final Mode mode;

        Scope(String tenantId, Set<Long> deptIds, String userId, Mode mode) {
            this.tenantId = tenantId;
            this.deptIds = deptIds == null ? Set.of() : deptIds;
            this.userId = userId;
            this.mode = mode;
        }

        public boolean isUnrestricted() {
            return mode == Mode.UNRESTRICTED;
        }

        public boolean isTenantAdmin() {
            return mode == Mode.TENANT;
        }

        /**
         * 文档查询条件。
         *
         * @return wrapper
         */
        public LambdaQueryWrapper<KnowledgeDocumentEntity> documents() {
            LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            if (isTenantAdmin()) {
                wrapper.and(q -> q.eq(KnowledgeDocumentEntity::getTenantId, tenantId)
                        .or()
                        .eq(KnowledgeDocumentEntity::getTenantId, "0")
                        .eq(KnowledgeDocumentEntity::getVisibility, VISIBILITY_PUBLIC));
                return wrapper;
            }
            wrapper.and(q -> q
                    .and(t -> t.eq(KnowledgeDocumentEntity::getTenantId, tenantId)
                            .and(v -> {
                                v.eq(KnowledgeDocumentEntity::getVisibility, VISIBILITY_PERSONAL)
                                        .eq(KnowledgeDocumentEntity::getOwner, userId);
                                if (!deptIds.isEmpty()) {
                                    v.or().eq(KnowledgeDocumentEntity::getVisibility, VISIBILITY_DEPT)
                                            .eq(KnowledgeDocumentEntity::getReviewStatus, REVIEW_APPROVED)
                                            .in(KnowledgeDocumentEntity::getDeptId, deptIds);
                                }
                                v.or().eq(KnowledgeDocumentEntity::getVisibility, VISIBILITY_TENANT)
                                        .eq(KnowledgeDocumentEntity::getReviewStatus, REVIEW_APPROVED);
                                if (canReview()) {
                                    if (!deptIds.isEmpty()) {
                                        v.or().eq(KnowledgeDocumentEntity::getReviewStatus, REVIEW_PENDING_DEPT)
                                                .in(KnowledgeDocumentEntity::getDeptId, deptIds);
                                    }
                                    v.or().eq(KnowledgeDocumentEntity::getReviewStatus, REVIEW_PENDING_TENANT);
                                }
                            }))
                    .or(pub -> pub.eq(KnowledgeDocumentEntity::getTenantId, "0")
                            .eq(KnowledgeDocumentEntity::getVisibility, VISIBILITY_PUBLIC)));
            return wrapper;
        }

        /**
         * 案例查询条件。
         *
         * @return wrapper
         */
        public LambdaQueryWrapper<KnowledgeCaseEntity> cases() {
            LambdaQueryWrapper<KnowledgeCaseEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            if (isTenantAdmin()) {
                wrapper.eq(KnowledgeCaseEntity::getTenantId, tenantId);
                return wrapper;
            }
            wrapper.eq(KnowledgeCaseEntity::getTenantId, tenantId)
                    .and(v -> {
                        v.eq(KnowledgeCaseEntity::getVisibility, VISIBILITY_TENANT);
                        if (!deptIds.isEmpty()) {
                            v.or().eq(KnowledgeCaseEntity::getVisibility, VISIBILITY_DEPT)
                                    .in(KnowledgeCaseEntity::getDeptId, deptIds);
                        }
                    });
            return wrapper;
        }

        /**
         * 冲突查询条件。
         *
         * @return wrapper
         */
        public LambdaQueryWrapper<KnowledgeConflictEntity> conflicts() {
            LambdaQueryWrapper<KnowledgeConflictEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            wrapper.eq(KnowledgeConflictEntity::getTenantId, tenantId);
            if (!isTenantAdmin()) {
                if (deptIds.isEmpty()) {
                    wrapper.eq(KnowledgeConflictEntity::getDeptId, -1L);
                } else {
                    wrapper.in(KnowledgeConflictEntity::getDeptId, deptIds);
                }
            }
            return wrapper;
        }

        /**
         * 命中流水查询条件。
         *
         * @return wrapper
         */
        public LambdaQueryWrapper<KnowledgeHitLogEntity> hitLogs() {
            LambdaQueryWrapper<KnowledgeHitLogEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            wrapper.eq(KnowledgeHitLogEntity::getTenantId, tenantId);
            if (!isTenantAdmin()) {
                if (deptIds.isEmpty()) {
                    wrapper.eq(KnowledgeHitLogEntity::getDeptId, -1L);
                } else {
                    wrapper.in(KnowledgeHitLogEntity::getDeptId, deptIds);
                }
            }
            return wrapper;
        }

        /**
         * 服务查询条件。
         *
         * @return wrapper
         */
        public LambdaQueryWrapper<OpsServiceEntity> services() {
            LambdaQueryWrapper<OpsServiceEntity> wrapper = new LambdaQueryWrapper<>();
            if (isUnrestricted()) {
                return wrapper;
            }
            wrapper.eq(OpsServiceEntity::getTenantId, tenantId);
            if (!isTenantAdmin()) {
                if (deptIds.isEmpty()) {
                    wrapper.eq(OpsServiceEntity::getDeptId, -1L);
                } else {
                    wrapper.in(OpsServiceEntity::getDeptId, deptIds);
                }
            }
            return wrapper;
        }

        /**
         * 判断单篇文档是否可见。
         *
         * @param doc 文档
         * @return true=可见
         */
        public boolean canAccess(KnowledgeDocumentEntity doc) {
            if (isUnrestricted()) {
                return true;
            }
            if (doc == null) {
                return false;
            }
            String visibility = doc.getVisibility();
            if (!StringUtils.hasText(visibility)) {
                return "BUILTIN".equalsIgnoreCase(doc.getLayer());
            }
            if (VISIBILITY_PUBLIC.equalsIgnoreCase(visibility)) {
                return true;
            }
            if (isTenantAdmin()) {
                return tenantId.equals(doc.getTenantId());
            }
            if (VISIBILITY_PERSONAL.equalsIgnoreCase(visibility)) {
                return StringUtils.hasText(userId) && userId.equals(doc.getOwner());
            }
            // 审核人可见本部门子树 / 本企业内待审核文档
            if (canReview()) {
                if (REVIEW_PENDING_DEPT.equalsIgnoreCase(doc.getReviewStatus())) {
                    return tenantId.equals(doc.getTenantId())
                            && doc.getDeptId() != null && deptIds.contains(doc.getDeptId());
                }
                if (REVIEW_PENDING_TENANT.equalsIgnoreCase(doc.getReviewStatus())) {
                    return tenantId.equals(doc.getTenantId());
                }
            }
            if (!tenantId.equals(doc.getTenantId()) || !isApproved(doc.getReviewStatus())) {
                return false;
            }
            if (VISIBILITY_TENANT.equalsIgnoreCase(visibility)) {
                return true;
            }
            if (VISIBILITY_DEPT.equalsIgnoreCase(visibility)) {
                return doc.getDeptId() != null && deptIds.contains(doc.getDeptId());
            }
            return false;
        }

        /**
         * 判断冲突记录是否可见。
         *
         * @param conflict 冲突记录
         * @return true=可见
         */
        public boolean canAccess(KnowledgeConflictEntity conflict) {
            if (isUnrestricted()) {
                return true;
            }
            if (conflict == null || !tenantId.equals(conflict.getTenantId())) {
                return false;
            }
            return isTenantAdmin() || (conflict.getDeptId() != null && deptIds.contains(conflict.getDeptId()));
        }

        /**
         * 判断案例是否可见。
         *
         * @param entity 案例
         * @return true=可见
         */
        public boolean canAccess(KnowledgeCaseEntity entity) {
            if (isUnrestricted()) {
                return true;
            }
            if (entity == null || !tenantId.equals(entity.getTenantId())) {
                return false;
            }
            if (isTenantAdmin()) {
                return true;
            }
            String visibility = entity.getVisibility();
            if (!StringUtils.hasText(visibility) || VISIBILITY_TENANT.equalsIgnoreCase(visibility)) {
                return true;
            }
            if (VISIBILITY_DEPT.equalsIgnoreCase(visibility)) {
                return entity.getDeptId() != null && deptIds.contains(entity.getDeptId());
            }
            if (VISIBILITY_PERSONAL.equalsIgnoreCase(visibility)) {
                return StringUtils.hasText(userId) && userId.equals(entity.getConfirmedBy());
            }
            return false;
        }

        private boolean isApproved(String reviewStatus) {
            return !StringUtils.hasText(reviewStatus) || REVIEW_APPROVED.equalsIgnoreCase(reviewStatus);
        }

        private boolean canReview() {
            return SecurityUtils.hasPermission("knowledge:review");
        }
    }
}
