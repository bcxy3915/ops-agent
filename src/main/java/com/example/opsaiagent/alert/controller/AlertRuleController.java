package com.example.opsaiagent.alert.controller;

import com.example.opsaiagent.alert.dto.AlertRuleRequest;
import com.example.opsaiagent.alert.dto.AlertRuleResponse;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.service.AlertRuleService;
import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 告警规则管理接口
 */
@Tag(name = "告警规则", description = "告警规则的增删改查与启停")
@Slf4j
@RestController
@RequestMapping("/api/alerts/rules")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    @Operation(summary = "查询告警规则列表", description = "可按服务 / 启用状态过滤。所有角色可访问")
    @GetMapping
    public ApiResponse<List<AlertRuleResponse>> list(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.success(
                alertRuleService.list(service, enabled).stream().map(this::toResponse).toList());
    }

    @Operation(summary = "创建告警规则", description = "需要 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "CREATE_ALERT_RULE", description = "创建告警规则")
    @PostMapping
    public ApiResponse<AlertRuleResponse> create(@Valid @RequestBody AlertRuleRequest request) {
        return ApiResponse.success(toResponse(alertRuleService.create(request)));
    }

    @Operation(summary = "更新告警规则", description = "需要 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "UPDATE_ALERT_RULE", description = "更新告警规则")
    @PutMapping("/{id}")
    public ApiResponse<AlertRuleResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody AlertRuleRequest request) {
        return ApiResponse.success(toResponse(alertRuleService.update(id, request)));
    }

    @Operation(summary = "启用 / 停用告警规则", description = "需要 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "TOGGLE_ALERT_RULE", description = "切换告警规则启停")
    @PutMapping("/{id}/enabled")
    public ApiResponse<AlertRuleResponse> setEnabled(@PathVariable Long id,
                                                     @RequestBody Map<String, Boolean> payload) {
        boolean enabled = payload == null || payload.getOrDefault("enabled", Boolean.TRUE);
        return ApiResponse.success(toResponse(alertRuleService.setEnabled(id, enabled)));
    }

    @Operation(summary = "删除告警规则", description = "需要 ADMIN 角色")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "DELETE_ALERT_RULE", description = "删除告警规则")
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        alertRuleService.delete(id);
        return ApiResponse.success(Map.of("deleted", id));
    }

    private AlertRuleResponse toResponse(AlertRuleEntity entity) {
        return alertRuleService.toResponse(entity);
    }
}
