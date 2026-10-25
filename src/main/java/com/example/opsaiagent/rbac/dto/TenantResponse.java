package com.example.opsaiagent.rbac.dto;

import com.example.opsaiagent.rbac.entity.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "租户信息")
public class TenantResponse {

    @Schema(description = "租户 ID")
    private String id;

    @Schema(description = "租户编码")
    private String code;

    @Schema(description = "企业名称")
    private String name;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "知识审核模式")
    private String knowledgeReviewMode;

    @Schema(description = "是否启用四眼原则")
    private Boolean reviewFourEyes;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /**
     * 实体转响应。
     *
     * @param entity 租户实体
     * @return 租户响应
     */
    public static TenantResponse from(TenantEntity entity) {
        if (entity == null) {
            return null;
        }
        return TenantResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .status(entity.getStatus())
                .knowledgeReviewMode(entity.getKnowledgeReviewMode())
                .reviewFourEyes(entity.getReviewFourEyes())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
