package com.example.opsaiagent.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新租户请求（字段可选）。
 */
@Data
@Schema(description = "更新租户请求")
public class TenantUpdateRequest {

    @Schema(description = "企业名称")
    @Size(max = 128, message = "企业名称最多 128 字")
    private String name;

    @Schema(description = "知识审核模式", allowableValues = {"DEPT_ONLY", "DEPT_AND_TENANT", "NONE"})
    @Pattern(regexp = "DEPT_ONLY|DEPT_AND_TENANT|NONE", message = "审核模式不合法")
    private String knowledgeReviewMode;

    @Schema(description = "是否启用四眼原则")
    private Boolean reviewFourEyes;

    @Schema(description = "状态", allowableValues = {"ACTIVE", "SUSPENDED"})
    @Pattern(regexp = "ACTIVE|SUSPENDED", message = "状态不合法")
    private String status;
}
