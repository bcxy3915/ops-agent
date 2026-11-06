package com.example.opsaiagent.rbac.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.rbac.dto.PermissionResponse;
import com.example.opsaiagent.rbac.dto.RolePermissionUpdateRequest;
import com.example.opsaiagent.rbac.dto.RoleResponse;
import com.example.opsaiagent.rbac.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色与权限管理接口。
 *
 * 角色列表供用户管理选择角色；权限点与角色权限维护仅平台管理员可用。
 */
@Tag(name = "角色权限", description = "角色列表、权限点列表、角色权限维护")
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "角色列表")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('user:manage')")
    @GetMapping
    public ApiResponse<List<RoleResponse>> listRoles() {
        return ApiResponse.success(roleService.listRoles().stream()
                .map(RoleResponse::from)
                .toList());
    }

    @Operation(summary = "权限点列表", description = "按模块返回全部可分配权限")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionResponse>> listPermissions() {
        return ApiResponse.success(roleService.listPermissions().stream()
                .map(PermissionResponse::from)
                .toList());
    }

    @Operation(summary = "角色权限查询")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{id}/permissions")
    public ApiResponse<List<Long>> getPermissions(@PathVariable Long id) {
        return ApiResponse.success(roleService.getPermissionIds(id));
    }

    @Operation(summary = "更新角色权限", description = "覆盖式更新；空数组表示清空")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(operation = "UPDATE_ROLE_PERMISSION", description = "更新角色权限")
    @PutMapping("/{id}/permissions")
    public ApiResponse<Void> updatePermissions(@PathVariable Long id,
                                               @Valid @RequestBody RolePermissionUpdateRequest request) {
        roleService.updatePermissions(id, request.getPermissionIds());
        return ApiResponse.success(null);
    }
}
