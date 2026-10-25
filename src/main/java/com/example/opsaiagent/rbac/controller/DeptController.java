package com.example.opsaiagent.rbac.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.dto.DeptCreateRequest;
import com.example.opsaiagent.rbac.dto.DeptResponse;
import com.example.opsaiagent.rbac.dto.DeptUpdateRequest;
import com.example.opsaiagent.rbac.service.DeptService;
import com.example.opsaiagent.security.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理接口。
 *
 * 企业管理员维护本企业部门树；平台管理员可通过 tenantId 指定企业。
 */
@Tag(name = "部门管理", description = "部门树查询、创建、编辑、删除")
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    @Operation(summary = "部门列表", description = "返回扁平列表，前端按 parentId 组树")
    @PreAuthorize("hasAuthority('dept:read')")
    @GetMapping
    public ApiResponse<List<DeptResponse>> list(@RequestParam(required = false) String tenantId) {
        String tid = resolveTenantId(tenantId);
        return ApiResponse.success(deptService.listByTenant(tid).stream()
                .map(DeptResponse::from)
                .toList());
    }

    @Operation(summary = "部门详情")
    @PreAuthorize("hasAuthority('dept:read')")
    @GetMapping("/{id}")
    public ApiResponse<DeptResponse> get(@PathVariable Long id,
                                         @RequestParam(required = false) String tenantId) {
        String tid = resolveTenantId(tenantId);
        return ApiResponse.success(DeptResponse.from(deptService.requireDept(tid, id)));
    }

    @Operation(summary = "创建部门")
    @PreAuthorize("hasAuthority('dept:manage')")
    @AuditLog(operation = "CREATE_DEPT", description = "创建部门")
    @PostMapping
    public ApiResponse<DeptResponse> create(@Valid @RequestBody DeptCreateRequest request,
                                            @RequestParam(required = false) String tenantId) {
        String tid = resolveTenantId(tenantId);
        return ApiResponse.success(DeptResponse.from(deptService.create(tid, request)));
    }

    @Operation(summary = "更新部门", description = "暂不支持修改上级部门")
    @PreAuthorize("hasAuthority('dept:manage')")
    @AuditLog(operation = "UPDATE_DEPT", description = "更新部门")
    @PutMapping("/{id}")
    public ApiResponse<DeptResponse> update(@PathVariable Long id,
                                            @Valid @RequestBody DeptUpdateRequest request,
                                            @RequestParam(required = false) String tenantId) {
        String tid = resolveTenantId(tenantId);
        return ApiResponse.success(DeptResponse.from(deptService.update(tid, id, request)));
    }

    @Operation(summary = "删除部门", description = "存在子部门或用户时拒绝删除")
    @PreAuthorize("hasAuthority('dept:manage')")
    @AuditLog(operation = "DELETE_DEPT", description = "删除部门")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestParam(required = false) String tenantId) {
        String tid = resolveTenantId(tenantId);
        deptService.delete(tid, id);
        return ApiResponse.success(null);
    }

    /**
     * 解析租户：企业用户强制使用当前租户，平台用户可显式指定。
     *
     * @param requested 请求参数中的租户 ID
     * @return 租户 ID
     */
    private String resolveTenantId(String requested) {
        String current = SecurityUtils.getCurrentTenantId();
        if (current != null && !current.isBlank() && !"0".equals(current)) {
            return current;
        }
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        throw new BusinessException(ErrorCode.TENANT_REQUIRED, "缺少租户上下文");
    }
}
