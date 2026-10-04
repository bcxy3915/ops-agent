package com.example.opsaiagent.security.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.UserResponse;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.service.OpsUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "查询所有用户", description = "需要 ADMIN 角色，返回的密码/手机号/邮箱已脱敏")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ApiResponse<List<UserResponse>> list() {
        List<UserResponse> users = userService.listAll().stream()
                .map(this::toResponse)
                .toList();
        return ApiResponse.success(users);
    }

    @Operation(summary = "查询单个用户", description = "需要 ADMIN 角色")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<UserResponse> get(@PathVariable String id) {
        OpsUserEntity user = userService.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "用户不存在: " + id));
        return ApiResponse.success(toResponse(user));
    }

    /**
     * 将 OpsUserEntity 转换为 UserResponse
     * @param entity 用户实体
     * @return 用户响应
     */
    private UserResponse toResponse(OpsUserEntity entity) {
        return UserResponse.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .password(entity.getPassword())
                .role(entity.getRole())
                .enabled(entity.getEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                // phone / email 暂时用假数据演示脱敏效果
                .phone("13812345678")
                .email("admin@example.com")
                .build();
    }
}