package com.example.opsaiagent.security.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.dto.UserResponse;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.service.OpsUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用户管理", description = "用户查询（仅 ADMIN）")
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
                        ErrorCode.SERVICE_NOT_FOUND, "用户不存在: " + id));
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

    /**
     * Entity → Response DTO
     * password/phone/email 由 UserResponse 的 @Sensitive 注解自动脱敏，
     * @param entity 用户实体
     * @return 用户响应DTO
     */
    private UserResponse toResponse(OpsUserEntity entity) {
        return UserResponse.builder()
                .id(entity.getId())
                .username(entity.getUsername())
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