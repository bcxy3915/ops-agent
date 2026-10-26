package com.example.opsaiagent.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 更新角色权限请求。
 */
@Data
@Schema(description = "更新角色权限请求")
public class RolePermissionUpdateRequest {

    @Schema(description = "权限 ID 列表；传空数组表示清空", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "permissionIds 不能为空")
    private List<Long> permissionIds;
}
