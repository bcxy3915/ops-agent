package com.example.opsaiagent.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新部门请求（字段可选）。
 *
 * 暂不支持修改上级部门，避免一次变更影响整棵子树；后续可单独提供移动接口。
 */
@Data
@Schema(description = "更新部门请求")
public class DeptUpdateRequest {

    @Schema(description = "部门名称")
    @Size(max = 128, message = "部门名称最多 128 字")
    private String name;

    @Schema(description = "同级排序")
    private Integer sort;

    @Schema(description = "状态", allowableValues = {"ACTIVE", "DISABLED"})
    @Pattern(regexp = "ACTIVE|DISABLED", message = "部门状态不合法")
    private String status;
}
