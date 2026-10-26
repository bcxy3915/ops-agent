package com.example.opsaiagent.security.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.service.DeptService;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.dto.UserResponse;
import com.example.opsaiagent.security.dto.UserUpdateRequest;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.service.OpsUserService;
import com.example.opsaiagent.security.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理接口。
 *
 * 租户管理员管理本企业用户；部门管理员只能查看本部门及下级部门用户；
 * 平台管理员可通过 tenantId 查看指定企业。
 */
@Tag(name = "用户管理", description = "用户查询、创建、更新、删除")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final OpsUserService userService;
    private final DeptService deptService;

    @Operation(summary = "查询用户列表", description = "支持 keyword/role/enabled/tenantId/deptId 筛选")
    @PreAuthorize("hasAuthority('user:read')")
    @GetMapping
    public ApiResponse<List<UserResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) Long deptId) {
        UserScope scope = resolveScope(tenantId, deptId);
        List<OpsUserEntity> list = userService.listByConditions(
                keyword, role, enabled, scope.tenantId(), scope.deptIds());
        return ApiResponse.success(list.stream().map(this::toResponse).toList());
    }

    @Operation(summary = "查询单个用户")
    @PreAuthorize("hasAuthority('user:read')")
    @GetMapping("/{id}")
    public ApiResponse<UserResponse> get(@PathVariable String id) {
        OpsUserEntity user = userService.getAccessibleUser(id);
        userService.decryptSensitiveFields(user);
        return ApiResponse.success(toResponse(user));
    }

    @Operation(summary = "创建用户", description = "支持指定租户、部门与多角色")
    @PreAuthorize("hasAuthority('user:manage')")
    @AuditLog(operation = "CREATE_USER", description = "创建用户")
    @PostMapping
    public ApiResponse<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        OpsUserEntity user = userService.create(request);
        return ApiResponse.success(toResponse(user));
    }

    @Operation(summary = "更新用户", description = "修改部门、多角色、启停、密码、手机、邮箱")
    @PreAuthorize("hasAuthority('user:manage')")
    @AuditLog(operation = "UPDATE_USER", description = "更新用户")
    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(@PathVariable String id,
                                            @Valid @RequestBody UserUpdateRequest request) {
        OpsUserEntity user = userService.update(id, request);
        userService.decryptSensitiveFields(user);
        return ApiResponse.success(toResponse(user));
    }

    @Operation(summary = "删除用户", description = "不能删除自己/内置 admin；系统至少保留一个 ADMIN")
    @PreAuthorize("hasAuthority('user:manage')")
    @AuditLog(operation = "DELETE_USER", description = "删除用户")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return ApiResponse.success(null);
    }

    /**
     * 计算当前用户的查询范围。
     *
     * @param requestedTenant 请求租户
     * @param deptId          请求部门
     * @return 租户 + 可见部门范围
     */
    private UserScope resolveScope(String requestedTenant, Long deptId) {
        String current = SecurityUtils.getCurrentTenantId();
        if (isPlatformTenant(current)) {
            String tenant = requestedTenant == null || requestedTenant.isBlank() ? null : requestedTenant;
            List<Long> deptIds = null;
            if (deptId != null) {
                if (tenant == null) {
                    throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                            "平台按部门查询用户时必须指定 tenantId");
                }
                deptIds = deptService.subtreeIds(tenant, deptId);
            }
            return new UserScope(tenant, deptIds);
        }

        if (requestedTenant != null && !requestedTenant.isBlank()
                && !requestedTenant.equals(current)) {
            throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能跨租户查询用户");
        }
        Long effectiveDept = deptId != null ? deptId : SecurityUtils.getCurrentDeptId();
        List<Long> deptIds = effectiveDept == null
                ? null : deptService.subtreeIds(current, effectiveDept);
        return new UserScope(current, deptIds);
    }

    private boolean isPlatformTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() || "0".equals(tenantId);
    }

    /**
     * Entity → Response DTO
     * password/phone/email 在 UserResponse 中被 @Sensitive 注解自动脱敏
     *
     * @param entity 用户实体
     * @return 用户响应DTO
     */
    private UserResponse toResponse(OpsUserEntity entity) {
        return UserResponse.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .displayName(entity.getDisplayName())
                .tenantId(entity.getTenantId())
                .deptId(entity.getDeptId())
                .roles(userService.getRoleCodes(entity.getId()))
                .password(entity.getPassword())    // 会被 SensitiveSerializer 脱敏
                .phone(entity.getPhone())          // 会被脱敏
                .email(entity.getEmail())          // 会被脱敏
                .role(entity.getRole())
                .enabled(entity.getEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * 用户查询范围。
     *
     * @param tenantId 租户 ID；null=不限
     * @param deptIds  部门 ID；null=不限，空=无可见
     */
    private record UserScope(String tenantId, List<Long> deptIds) {
    }
}
