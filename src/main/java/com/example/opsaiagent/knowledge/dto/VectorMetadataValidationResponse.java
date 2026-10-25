package com.example.opsaiagent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 向量 metadata 校验结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorMetadataValidationResponse {

    /** 向量总数 */
    private long total;

    /** metadata 完整且符合规则的数量 */
    private long valid;

    /** 缺少 tenantId */
    private long missingTenantId;

    /** 缺少 visibility */
    private long missingVisibility;

    /** 缺少 reviewStatus */
    private long missingReviewStatus;

    /** 缺少 owner */
    private long missingOwner;

    /** visibility 非法 */
    private long invalidVisibility;

    /** reviewStatus 非法 */
    private long invalidReviewStatus;

    /** 内置知识不是 PUBLIC */
    private long builtinNotPublic;

    /** 内置知识 tenantId 不是 0 */
    private long builtinTenantNotPlatform;

    /** 案例知识不是 TENANT */
    private long caseNotTenant;

    /** 问题明细（最多 20 条） */
    private List<String> issues;
}
