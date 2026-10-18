package com.example.opsaiagent.alert.controller;

import com.example.opsaiagent.alert.dto.AlertEventResponse;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.service.AlertEventService;
import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警事件查询与处置接口
 */
@Tag(name = "告警事件", description = "告警事件查询与确认")
@Slf4j
@RestController
@RequestMapping("/api/alerts/events")
@RequiredArgsConstructor
public class AlertEventController {

    private final AlertEventService alertEventService;

    @Operation(summary = "查询告警事件列表", description = "可按服务 / 状态 / 规则过滤。所有角色可访问")
    @GetMapping
    public ApiResponse<List<AlertEventResponse>> list(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(
                alertEventService.list(service, status, ruleId, limit).stream().map(this::toResponse).toList());
    }

    @Operation(summary = "确认告警事件", description = "将 FIRING / SILENCED 事件标记为已确认。需要 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "ACK_ALERT_EVENT", description = "确认告警事件")
    @PostMapping("/{id}/acknowledge")
    public ApiResponse<AlertEventResponse> acknowledge(@PathVariable Long id) {
        return ApiResponse.success(toResponse(alertEventService.acknowledge(id)));
    }

    private AlertEventResponse toResponse(AlertEventEntity entity) {
        return alertEventService.toResponse(entity);
    }
}
