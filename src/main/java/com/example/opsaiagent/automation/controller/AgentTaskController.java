package com.example.opsaiagent.automation.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.automation.dto.AgentTaskDetail;
import com.example.opsaiagent.automation.dto.AgentTaskSummary;
import com.example.opsaiagent.automation.service.AgentTaskQueryService;
import com.example.opsaiagent.automation.service.InspectionTaskService;
import com.example.opsaiagent.automation.diagnosis.AutonomousDiagnosisService;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 持久化巡检任务入口，与既有即时巡检接口分别使用。 */
@RestController
@RequestMapping("/api/agent-tasks")
@RequiredArgsConstructor
public class AgentTaskController {
    private final InspectionTaskService inspections;
    private final AgentTaskQueryService queries;
    private final AutonomousDiagnosisService diagnoses;

    /**
     * 创建持久化有界自主诊断任务，不允许部署或修改目标。
     * @param serviceId 已登记服务 ID
     * @return 任务、动态步骤与诊断证据
     */
    @Operation(summary = "创建并执行只读自主诊断")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:trigger')")
    @RateLimit(key = "autonomousDiagnosis", limit = 5, period = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(operation = "AUTONOMOUS_DIAGNOSIS", description = "执行有界只读自主诊断")
    @PostMapping("/services/{serviceId}/diagnosis")
    public ApiResponse<AgentTaskDetail> diagnose(@PathVariable String serviceId) {
        return ApiResponse.success(diagnoses.diagnose(serviceId));
    }

    /**
     * 同步执行一次具有持久化记录的只读巡检。
     * @param serviceId 已登记服务 ID
     * @return 任务、步骤与证据详情
     */
    @Operation(summary = "创建并执行持久化巡检任务")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:trigger')")
    @RateLimit(key = "inspectionTask", limit = 10, period = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(operation = "CREATE_INSPECTION_TASK", description = "创建并执行巡检任务")
    @PostMapping("/services/{serviceId}/inspection")
    public ApiResponse<AgentTaskDetail> run(@PathVariable String serviceId) {
        return ApiResponse.success(inspections.run(serviceId));
    }

    /**
     * 读取当前身份可见的任务详情。
     * @param taskId 任务 ID
     * @return 任务详情
     */
    @Operation(summary = "查询巡检任务详情")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:read')")
    @GetMapping("/{taskId}")
    public ApiResponse<AgentTaskDetail> detail(@PathVariable String taskId) {
        return ApiResponse.success(queries.detail(taskId));
    }

    /**
     * 有界查询当前服务最近任务。
     * @param serviceId 服务 ID
     * @param limit 上限 1～50，默认 20
     * @return 任务摘要列表
     */
    @Operation(summary = "查询服务最近巡检任务")
    @PreAuthorize("hasAuthority('service:read') and hasAuthority('diagnosis:read')")
    @GetMapping("/services/{serviceId}")
    public ApiResponse<List<AgentTaskSummary>> list(@PathVariable String serviceId,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(queries.list(serviceId, limit));
    }
}
