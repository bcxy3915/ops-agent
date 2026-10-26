package com.example.opsaiagent.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建租户请求（平台管理员）。
 */
@Data
@Schema(description = "创建租户请求")
public class TenantCreateRequest {

    @Schema(description = "租户编码（全局唯一）", example = "XH", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "租户编码不能为空")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "租户编码只能包含字母、数字、下划线、连字符")
    @Size(max = 64, message = "租户编码最多 64 位")
    private String code;

    @Schema(description = "企业名称", example = "星海科技", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "企业名称不能为空")
    @Size(max = 128, message = "企业名称最多 128 字")
    private String name;

    @Schema(description = "知识审核模式", allowableValues = {"DEPT_ONLY", "DEPT_AND_TENANT", "NONE"})
    @Pattern(regexp = "DEPT_ONLY|DEPT_AND_TENANT|NONE", message = "审核模式不合法")
    private String knowledgeReviewMode = "DEPT_ONLY";

    @Schema(description = "是否启用四眼原则", example = "true")
    private Boolean reviewFourEyes = Boolean.TRUE;
}
