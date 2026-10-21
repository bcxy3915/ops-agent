package com.example.opsaiagent.security.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.dto.UserResponse;
import com.example.opsaiagent.security.dto.UserUpdateRequest;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.service.OpsUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用户管理", description = "用户查询、创建、更新、删除（仅 ADMIN）")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final OpsUserService userService;

    @Operation(summary = "查询用户列表", description = "支持 keyword/role/enabled 筛选")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ApiResponse<List<UserResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled) {
        List<OpsUserEntity> list = userService.listByConditions(keyword, role, enabled);
        return ApiResponse.success(list.stream().map(this::toResponse).toList());
    }

    @Operation(summary = "查询单个用户")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<UserResponse> get(@PathVariable String id) {
        OpsUserEntity user = userService.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.USER_NOT_FOUND, "用户不存在: " + id));
        userService.decryptSensitiveFields(user);
        return ApiResponse.success(toResponse(user));
    }

    @Operation(summary = "创建用户", description = "需要 ADMIN 角色")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "CREATE_USER", description = "创建用户")
    @PostMapping
    public ApiResponse<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        OpsUserEntity user = userService.create(request);
        return ApiResponse.success(toResponse(user));
    }

    @Operation(summary = "更新用户", description = "修改角色/启停/密码/手机/邮箱。需要 ADMIN 角色")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "UPDATE_USER", description = "更新用户")
    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(@PathVariable String id,
                                            @Valid @RequestBody UserUpdateRequest request) {
        OpsUserEntity user = userService.update(id, request);
        userService.decryptSensitiveFields(user);
        return ApiResponse.success(toResponse(user));
    }

    @Operation(summary = "删除用户", description = "不能删除自己/内置 admin；系统至少保留一个 ADMIN。需要 ADMIN 角色")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "DELETE_USER", description = "删除用户")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return ApiResponse.success(null);
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
                .password(entity.getPassword())    // 会被 SensitiveSerializer 脱敏
                .phone(entity.getPhone())          // 会被脱敏
                .email(entity.getEmail())          // 会被脱敏
                .role(entity.getRole())
                .enabled(entity.getEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
