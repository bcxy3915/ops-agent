package com.example.opsaiagent.inspection.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.inspection.dto.MetricWindowReport;
import com.example.opsaiagent.inspection.service.InspectionService;
import com.example.opsaiagent.inspection.service.InspectionMetricWindowService;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读巡检入口，独立于已有问答与故障快照诊断。 */
@RestController
@RequestMapping("/api/inspections")
@RequiredArgsConstructor
public class InspectionController {
    private final InspectionService inspectionService;
    private final InspectionMetricWindowService metricWindows;

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

    /**
     * 获取两个相邻时间窗的有界指标摘要。
     * @param serviceId 已登记服务 ID
     * @param metric 白名单 Actuator 指标名
     * @param windowMinutes 单窗口分钟数，默认 5
     * @param endMs 当前窗口终点，未传时使用当前时间戳
     * @return 统一封装的窗口对比证据
     */
    @Operation(summary = "巡检指标时间窗对比")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:trigger')")
    @RateLimit(key = "inspectionWindow", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @GetMapping("/services/{serviceId}/metric-window")
    public ApiResponse<MetricWindowReport> metricWindow(@PathVariable String serviceId,
            @RequestParam String metric, @RequestParam(defaultValue = "5") int windowMinutes,
            @RequestParam(required = false) Long endMs) {
        return ApiResponse.success(metricWindows.compare(serviceId, metric, windowMinutes,
                endMs == null ? System.currentTimeMillis() : endMs));
    }
}
