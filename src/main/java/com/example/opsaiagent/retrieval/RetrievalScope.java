package com.example.opsaiagent.retrieval;

import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.security.util.SecurityUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识检索数据范围。
 *
 * 统一表达“当前用户能检索哪些知识”：
 * - PUBLIC：所有租户可见；
 * - TENANT：同租户且已审核通过；
 * - DEPT：同租户、已审核通过、部门在当前用户可见部门子树内；
 * - PERSONAL：owner 为当前用户。
 *
 * 该对象同时用于向量检索 filterExpression 与 BM25 查询期过滤。
 *
 * @param tenantId   当前租户；null 表示仅 PUBLIC
 * @param deptIds    可见部门；null 表示同租户全部部门，空集合表示无部门可见
 * @param userId     当前用户名；用于 PERSONAL 匹配
 */
public record RetrievalScope(String tenantId, Set<Long> deptIds, String userId) {

    /** 知识可见性：公开 */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    /** 知识可见性：企业 */
    public static final String VISIBILITY_TENANT = "TENANT";
    /** 知识可见性：部门 */
    public static final String VISIBILITY_DEPT = "DEPT";
    /** 知识可见性：个人 */
    public static final String VISIBILITY_PERSONAL = "PERSONAL";
    /** 审核状态：通过 */
    public static final String REVIEW_APPROVED = "APPROVED";

    /**
     * 仅公开知识范围（平台 / 无租户上下文）。
     *
     * @return 检索范围
     */
    public static RetrievalScope publicOnly() {
        return new RetrievalScope(null, null, null);
    }

    /**
     * 从当前请求上下文构建检索范围。
     *
     * @param dataScope 数据范围工具
     * @return 检索范围
     */
    public static RetrievalScope current(DataScope dataScope) {
        String tenantId = dataScope.tenantId();
        String userId = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(tenantId) || dataScope.isPlatformTenant()) {
            return new RetrievalScope(null, null, userId);
        }
        Set<Long> deptIds = null;
        if (dataScope.deptId() != null) {
            List<Long> visible = dataScope.visibleDeptIds();
            deptIds = visible == null ? Set.of() : new LinkedHashSet<>(visible);
        }
        return new RetrievalScope(tenantId, deptIds, userId);
    }

    /**
     * 判断向量 metadata 是否落入当前检索范围。
     *
     * @param metadata 向量 metadata
     * @return true=可见
     */
    public boolean matches(Map<String, Object> metadata) {
        Map<String, Object> md = metadata == null ? Map.of() : metadata;
        String visibility = str(md.get("visibility"));

        // 兼容旧内置知识：没有 visibility 时按 BUILTIN / 非上传来源视为 PUBLIC
        if (!StringUtils.hasText(visibility)) {
            String layer = str(md.get("layer"));
            String source = str(md.get("source"));
            boolean builtin = "BUILTIN".equalsIgnoreCase(layer)
                    || (StringUtils.hasText(source)
                    && !source.startsWith("upload:") && !source.startsWith("case:"));
            return builtin;
        }

        if (VISIBILITY_PUBLIC.equalsIgnoreCase(visibility)) {
            return true;
        }
        if (!StringUtils.hasText(tenantId)
                || !tenantId.equals(str(md.get("tenantId")))
                || !isApproved(md)) {
            return false;
        }
        if (VISIBILITY_TENANT.equalsIgnoreCase(visibility)) {
            return true;
        }
        if (VISIBILITY_DEPT.equalsIgnoreCase(visibility)) {
            Long deptId = parseLong(str(md.get("deptId")));
            if (deptId == null) {
                return false;
            }
            return deptIds == null || deptIds.contains(deptId);
        }
        if (VISIBILITY_PERSONAL.equalsIgnoreCase(visibility)) {
            return StringUtils.hasText(userId) && userId.equals(str(md.get("owner")));
        }
        return false;
    }

    /**
     * 生成 Spring AI 向量检索 filterExpression。
     *
     * 所有分支都必须带 reviewStatus == 'APPROVED'：审核结论是检索可见性的硬条件，
     * 草稿 / 待审核 / 已驳回内容即使残留向量也不得命中。
     *
     * @return filterExpression；无租户时仅 PUBLIC
     */
    public String filterExpression() {
        if (!StringUtils.hasText(tenantId)) {
            return "visibility == '" + VISIBILITY_PUBLIC + "' && " + approvedClause();
        }
        List<String> clauses = new ArrayList<>();
        clauses.add("(visibility == '" + VISIBILITY_PUBLIC + "' && " + approvedClause() + ")");
        clauses.add("(visibility == '" + VISIBILITY_TENANT + "' && tenantId == '"
                + escape(tenantId) + "' && " + approvedClause() + ")");

        // null 表示同租户全部部门；空集合表示无部门可见，不生成 DEPT 分支
        if (deptIds == null || !deptIds.isEmpty()) {
            StringBuilder deptClause = new StringBuilder()
                    .append("(visibility == '").append(VISIBILITY_DEPT).append("'")
                    .append(" && tenantId == '").append(escape(tenantId)).append("'")
                    .append(" && ").append(approvedClause());
            if (deptIds != null) {
                deptClause.append(" && (");
                int i = 0;
                for (Long deptId : deptIds) {
                    if (i++ > 0) {
                        deptClause.append(" || ");
                    }
                    deptClause.append("deptId == '").append(deptId).append("'");
                }
                deptClause.append(")");
            }
            deptClause.append(")");
            clauses.add(deptClause.toString());
        }

        if (StringUtils.hasText(userId)) {
            clauses.add("(visibility == '" + VISIBILITY_PERSONAL
                    + "' && owner == '" + escape(userId) + "' && " + approvedClause() + ")");
        }
        return String.join(" || ", clauses);
    }

    private String approvedClause() {
        return "reviewStatus == '" + REVIEW_APPROVED + "'";
    }

    private boolean isApproved(Map<String, Object> md) {
        String reviewStatus = str(md.get("reviewStatus"));
        // 兼容旧向量：没有 reviewStatus 视为已通过（旧数据只有审核通过后才入库）
        return !StringUtils.hasText(reviewStatus) || REVIEW_APPROVED.equalsIgnoreCase(reviewStatus);
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
