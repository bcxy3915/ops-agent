package com.example.opsaiagent.inspection.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.inspection.service.InspectionService;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读巡检入口，独立于已有问答与故障快照诊断。 */
@RestController
@RequestMapping("/api/inspections")
@RequiredArgsConstructor
public class InspectionController {
    private final InspectionService inspectionService;

    /**
     * 对已绑定服务发起一次实时巡检。
     * @param serviceId 服务登记 ID
     * @return 统一响应封装的观测与规则报告
     */
    @Operation(summary = "只读服务巡检")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:trigger')")
    @RateLimit(key = "inspection", limit = 10, period = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(operation = "INSPECT_SERVICE", description = "执行服务只读巡检")
    @PostMapping("/services/{serviceId}")
    public ApiResponse<InspectionReport> inspect(@PathVariable String serviceId) {
        return ApiResponse.success(inspectionService.inspect(serviceId));
    }
}
