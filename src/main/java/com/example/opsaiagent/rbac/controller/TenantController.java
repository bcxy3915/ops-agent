package com.example.opsaiagent.rbac.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.rbac.dto.TenantCreateRequest;
import com.example.opsaiagent.rbac.dto.TenantResponse;
import com.example.opsaiagent.rbac.dto.TenantUpdateRequest;
import com.example.opsaiagent.rbac.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户管理接口（平台管理员）。
 *
 * 平台侧维护企业租户：开通、编辑、启停。企业内用户/部门等管理由租户管理员完成。
 */
@Tag(name = "租户管理", description = "平台侧租户开通、编辑与启停")
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('tenant:manage')")
public class TenantController {

    private final TenantService tenantService;

    @Operation(summary = "租户列表", description = "平台管理员查看全部租户")
    @GetMapping
    public ApiResponse<List<TenantResponse>> list() {
        return ApiResponse.success(tenantService.listAll().stream()
                .map(TenantResponse::from)
                .toList());
    }

    @Operation(summary = "租户详情")
    @GetMapping("/{id}")
    public ApiResponse<TenantResponse> get(@PathVariable String id) {
        return ApiResponse.success(TenantResponse.from(tenantService.requireTenant(id)));
    }

    @Operation(summary = "开通租户", description = "平台管理员创建企业租户")
    @AuditLog(operation = "CREATE_TENANT", description = "开通租户")
    @PostMapping
    public ApiResponse<TenantResponse> create(@Valid @RequestBody TenantCreateRequest request) {
        return ApiResponse.success(TenantResponse.from(tenantService.create(request)));
    }

    @Operation(summary = "编辑租户")
    @AuditLog(operation = "UPDATE_TENANT", description = "编辑租户")
    @PutMapping("/{id}")
    public ApiResponse<TenantResponse> update(@PathVariable String id,
                                              @Valid @RequestBody TenantUpdateRequest request) {
        return ApiResponse.success(TenantResponse.from(tenantService.update(id, request)));
    }

    @Operation(summary = "启停租户", description = "ACTIVE / SUSPENDED")
    @AuditLog(operation = "UPDATE_TENANT_STATUS", description = "启停租户")
    @PutMapping("/{id}/status")
    public ApiResponse<TenantResponse> updateStatus(@PathVariable String id,
                                                    @RequestBody TenantUpdateRequest request) {
        return ApiResponse.success(TenantResponse.from(
                tenantService.updateStatus(id, request.getStatus())));
    }
}
