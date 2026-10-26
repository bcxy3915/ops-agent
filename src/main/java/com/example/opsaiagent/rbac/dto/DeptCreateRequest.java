package com.example.opsaiagent.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建部门请求。
 */
@Data
@Schema(description = "创建部门请求")
public class DeptCreateRequest {

    @Schema(description = "上级部门 ID；为空表示根部门", example = "1")
    private Long parentId;

    @Schema(description = "部门编码（租户内唯一）", example = "OPS", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "部门编码不能为空")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "部门编码只能包含字母、数字、下划线、连字符")
    @Size(max = 64, message = "部门编码最多 64 位")
    private String code;

    @Schema(description = "部门名称", example = "运维部", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "部门名称不能为空")
    @Size(max = 128, message = "部门名称最多 128 字")
    private String name;

    @Schema(description = "同级排序", example = "0")
    private Integer sort = 0;

    @Schema(description = "状态", allowableValues = {"ACTIVE", "DISABLED"})
    @Pattern(regexp = "ACTIVE|DISABLED", message = "部门状态不合法")
    private String status = "ACTIVE";
}
