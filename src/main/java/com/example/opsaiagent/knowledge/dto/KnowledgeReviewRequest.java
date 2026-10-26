package com.example.opsaiagent.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识文档审核请求。
 */
@Data
@Schema(description = "知识文档审核请求")
public class KnowledgeReviewRequest {

    @Schema(description = "审核层级", allowableValues = {"DEPT", "TENANT"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "审核层级不能为空")
    @Pattern(regexp = "DEPT|TENANT", message = "审核层级必须为 DEPT/TENANT")
    private String level;

    @Schema(description = "是否通过", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    @Schema(description = "审核意见 / 驳回原因")
    @Size(max = 500, message = "审核意见最多 500 字")
    private String comment;
}
