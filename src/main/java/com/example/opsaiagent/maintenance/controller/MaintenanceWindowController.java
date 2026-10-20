package com.example.opsaiagent.maintenance.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.maintenance.dto.MaintenanceWindowCreateRequest;
import com.example.opsaiagent.maintenance.dto.MaintenanceWindowResponse;
import com.example.opsaiagent.maintenance.service.MaintenanceWindowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 维护窗口接口（P1 规格 5）
 * 服务管理页页头「维护窗口」入口：多选服务 + 原因 + 到期时间。
 * 存量告警置静默；维护期内新告警落库但压制通知/升级；到期自动解除由评估器恢复。
 */
@Tag(name = "维护窗口", description = "服务维度维护窗口：创建、查询、提前结束")
@RestController
@RequestMapping("/api/maintenance/windows")
@RequiredArgsConstructor
public class MaintenanceWindowController {

    private final MaintenanceWindowService windowService;

    @Operation(summary = "创建维护窗口", description = "多选服务 + 原因 + 到期时间；内部对存量告警批量静默。需 ADMIN / OPERATOR")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "CREATE_MAINTENANCE_WINDOW", description = "创建维护窗口")
    @PostMapping
    public ApiResponse<List<MaintenanceWindowResponse>> create(@Valid @RequestBody MaintenanceWindowCreateRequest request) {
        return ApiResponse.success(windowService.create(request));
    }

    @Operation(summary = "查询维护窗口", description = "activeOnly=true 只返回未结束未到期窗口。所有角色可访问")
    @GetMapping
    public ApiResponse<List<MaintenanceWindowResponse>> list(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ApiResponse.success(windowService.list(activeOnly));
    }

    @Operation(summary = "提前结束维护窗口", description = "结束窗口；压制告警由到期恢复逻辑重新评估。需 ADMIN / OPERATOR")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "END_MAINTENANCE_WINDOW", description = "提前结束维护窗口")
    @DeleteMapping("/{id}")
    public ApiResponse<MaintenanceWindowResponse> end(@PathVariable Long id) {
        return ApiResponse.success(windowService.end(id));
    }
}
