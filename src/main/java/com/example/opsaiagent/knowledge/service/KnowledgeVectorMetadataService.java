package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.knowledge.dto.VectorMetadataValidationResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 向量 metadata 校验服务。
 *
 * 检查 vector_store 中 tenantId / visibility / reviewStatus / owner 是否完整，
 * 并校验内置知识 PUBLIC、案例知识 TENANT 等约定，供迁移后验收使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeVectorMetadataService {

    private static final Set<String> VISIBILITIES =
            Set.of("PUBLIC", "TENANT", "DEPT", "PERSONAL");
    private static final Set<String> REVIEW_STATUSES =
            Set.of("DRAFT", "PENDING_DEPT", "PENDING_TENANT", "APPROVED", "REJECTED");
    private static final int MAX_ISSUES = 20;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 校验全部向量 metadata。
     *
     * @return 校验统计与问题明细
     */
    public VectorMetadataValidationResponse validate() {
        List<String> rows = jdbcTemplate.query(
                "SELECT metadata::text FROM vector_store",
                (rs, rowNum) -> rs.getString(1));

        long total = rows.size();
        long valid = 0;
        long missingTenantId = 0;
        long missingVisibility = 0;
        long missingReviewStatus = 0;
        long missingOwner = 0;
        long invalidVisibility = 0;
        long invalidReviewStatus = 0;
        long builtinNotPublic = 0;
        long builtinTenantNotPlatform = 0;
        long caseNotTenant = 0;
        List<String> issues = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> metadata = parse(rows.get(i));
            String source = str(metadata.get("source"));
            String tenantId = str(metadata.get("tenantId"));
            String visibility = str(metadata.get("visibility"));
            String reviewStatus = str(metadata.get("reviewStatus"));
            String owner = str(metadata.get("owner"));
            List<String> reasons = new ArrayList<>();

            if (!StringUtils.hasText(tenantId)) {
                missingTenantId++;
                reasons.add("缺少 tenantId");
            }
            if (!StringUtils.hasText(visibility)) {
                missingVisibility++;
                reasons.add("缺少 visibility");
            } else if (!VISIBILITIES.contains(visibility)) {
                invalidVisibility++;
                reasons.add("visibility 非法: " + visibility);
            }
            if (!StringUtils.hasText(reviewStatus)) {
                missingReviewStatus++;
                reasons.add("缺少 reviewStatus");
            } else if (!REVIEW_STATUSES.contains(reviewStatus)) {
                invalidReviewStatus++;
                reasons.add("reviewStatus 非法: " + reviewStatus);
            }
            if (!StringUtils.hasText(owner)) {
                missingOwner++;
                reasons.add("缺少 owner");
            }

            boolean builtin = StringUtils.hasText(source)
                    && !source.startsWith("upload:") && !source.startsWith("case:");
            boolean caseDoc = StringUtils.hasText(source) && source.startsWith("case:");
            if (builtin) {
                if (!"PUBLIC".equals(visibility)) {
                    builtinNotPublic++;
                    reasons.add("内置知识 visibility 不是 PUBLIC");
                }
                if (!"0".equals(tenantId)) {
                    builtinTenantNotPlatform++;
                    reasons.add("内置知识 tenantId 不是 0");
                }
            }
            if (caseDoc && !"TENANT".equals(visibility)) {
                caseNotTenant++;
                reasons.add("案例知识 visibility 不是 TENANT");
            }

            if (reasons.isEmpty()) {
                valid++;
            } else if (issues.size() < MAX_ISSUES) {
                issues.add("row=" + (i + 1) + ", source=" + (source == null ? "" : source)
                        + ", " + String.join("; ", reasons));
            }
        }

        log.info("向量 metadata 校验完成: total={}, valid={}, issues={}", total, valid, issues.size());
        return VectorMetadataValidationResponse.builder()
                .total(total)
                .valid(valid)
                .missingTenantId(missingTenantId)
                .missingVisibility(missingVisibility)
                .missingReviewStatus(missingReviewStatus)
                .missingOwner(missingOwner)
                .invalidVisibility(invalidVisibility)
                .invalidReviewStatus(invalidReviewStatus)
                .builtinNotPublic(builtinNotPublic)
                .builtinTenantNotPlatform(builtinTenantNotPlatform)
                .caseNotTenant(caseNotTenant)
                .issues(issues)
                .build();
    }

    /**
     * 解析 metadata JSON。
     *
     * @param json metadata 文本
     * @return metadata map；解析失败返回空 map
     */
    private Map<String, Object> parse(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("向量 metadata 解析失败: {}", json, e);
            return Map.of();
        }
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }
}
