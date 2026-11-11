package com.example.opsaiagent.inspection.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.inspection.dto.MetricWindowReport;
import com.example.opsaiagent.inspection.dto.BusinessProbeReport;
import com.example.opsaiagent.inspection.service.BusinessProbeService;
import com.example.opsaiagent.inspection.dto.LogEvidenceReport;
import com.example.opsaiagent.inspection.service.InspectionLogService;
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
    private final BusinessProbeService businessProbes;
    private final InspectionLogService logEvidence;

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

    /**
     * 执行预登记业务接口的只读探针。
     * @param serviceId 服务登记 ID
     * @param probeId 服务端探针标识
     * @return 统一封装的业务证据
     */
    @Operation(summary = "只读业务接口探针")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:trigger')")
    @RateLimit(key = "inspectionBusiness", limit = 10, period = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(operation = "PROBE_BUSINESS", description = "执行只读业务探针")
    @PostMapping("/services/{serviceId}/business-probes/{probeId}")
    public ApiResponse<BusinessProbeReport> businessProbe(@PathVariable String serviceId,
                                                          @PathVariable String probeId) {
        return ApiResponse.success(businessProbes.check(serviceId, probeId));
    }

    /**
     * 查询绑定文件的脱敏错误日志，调用者不能传入路径。
     * @param serviceId 已登记服务 ID
     * @param sourceId 服务端日志源标识
     * @param startMs 起点毫秒时间戳，包含
     * @param endMs 终点毫秒时间戳，不包含
     * @param limit 条数限制，默认 100
     * @return 统一封装的日志证据
     */
    @Operation(summary = "受控日志证据查询")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:trigger')")
    @RateLimit(key = "inspectionLogs", limit = 10, period = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(operation = "READ_INSPECTION_LOGS", description = "查询受控日志证据")
    @GetMapping("/services/{serviceId}/logs/{sourceId}")
    public ApiResponse<LogEvidenceReport> logs(@PathVariable String serviceId, @PathVariable String sourceId,
            @RequestParam long startMs, @RequestParam long endMs, @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(logEvidence.read(serviceId, sourceId, startMs, endMs, limit));
    }
}
