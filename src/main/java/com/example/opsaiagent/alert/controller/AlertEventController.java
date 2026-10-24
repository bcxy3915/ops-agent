package com.example.opsaiagent.alert.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertEventResponse;
import com.example.opsaiagent.alert.dto.AlertEventStatus;
import com.example.opsaiagent.alert.dto.BatchAckRequest;
import com.example.opsaiagent.alert.dto.BatchReadRequest;
import com.example.opsaiagent.alert.dto.BatchSilenceRequest;
import com.example.opsaiagent.alert.dto.AlertSummaryResponse;
import com.example.opsaiagent.alert.dto.ResolveRequest;
import com.example.opsaiagent.alert.dto.SilenceRequest;
import com.example.opsaiagent.alert.entity.AlertEscalationRecordEntity;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEscalationRecordMapper;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.alert.service.AlertEventService;
import com.example.opsaiagent.alert.service.AlertReadService;
import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.diagnosis.mapper.DiagnosisRecordMapper;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.security.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 告警事件查询与处置接口
 * P0（对齐规格 v1）：已读与已处理拆分、状态机四态、认领/接管、静默/解除静默、批量确认、未读计数、升级记录
 */
@Tag(name = "告警事件", description = "告警事件查询、已读、确认/恢复/静默/认领")
@Slf4j
@RestController
@RequestMapping("/api/alerts/events")
@RequiredArgsConstructor
public class AlertEventController {

    private final AlertEventService alertEventService;
    private final AlertReadService alertReadService;
    private final AlertEventMapper alertEventMapper;
    private final AlertEscalationRecordMapper escalationRecordMapper;
    private final DiagnosisRecordMapper diagnosisRecordMapper;

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

    @Operation(summary = "查询告警事件详情", description = "所有角色可访问")
    @GetMapping("/{id}")
    public ApiResponse<AlertEventResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(toResponse(alertEventService.getById(id)));
    }

    @Operation(summary = "查询告警升级记录", description = "详情页处置时间线数据源（规格 4.2）")
    @GetMapping("/{id}/escalations")
    public ApiResponse<List<AlertEscalationRecordEntity>> escalations(@PathVariable Long id) {
        return ApiResponse.success(escalationRecordMapper.selectList(
                new LambdaQueryWrapper<AlertEscalationRecordEntity>()
                        .eq(AlertEscalationRecordEntity::getAlertEventId, id)
                        .orderByAsc(AlertEscalationRecordEntity::getLevel)));
    }

    @Operation(summary = "当前用户未读告警数", description = "铃铛 badge（规格 1.2 口径）：FIRING/ACK/SILENCED 且未读")
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Integer>> unreadCount() {
        return ApiResponse.success(Map.of("count", alertReadService.unreadCount(SecurityUtils.getCurrentUsername())));
    }

    @Operation(summary = "标记单条告警已读", description = "只影响已读状态，不改变处置状态。所有登录角色可访问")
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        alertReadService.markRead(id, SecurityUtils.getCurrentUsername());
        return ApiResponse.success(null);
    }

    @Operation(summary = "批量标记已读", description = "ids 为空时对全部待处理事件（FIRING/ACK/SILENCED）标记已读")
    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead(@RequestBody(required = false) @Valid BatchReadRequest request) {
        String username = SecurityUtils.getCurrentUsername();
        List<Long> ids = request == null ? null : request.getIds();
        if (ids == null || ids.isEmpty()) {
            List<AlertEventEntity> pending = alertEventMapper.selectList(
                    new LambdaQueryWrapper<AlertEventEntity>()
                            .in(AlertEventEntity::getStatus,
                                    AlertEventStatus.FIRING.name(),
                                    AlertEventStatus.ACKNOWLEDGED.name(),
                                    AlertEventStatus.SILENCED.name()));
            ids = pending.stream().map(AlertEventEntity::getId).toList();
        }
        alertReadService.markAllRead(ids, username);
        return ApiResponse.success(null);
    }

    @Operation(summary = "确认告警事件（自动认领）", description = "FIRING / SILENCED → ACKNOWLEDGED，记录认领人（规格 2.3）。需 ADMIN / OPERATOR")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "ACK_ALERT_EVENT", description = "确认告警事件")
    @PostMapping("/{id}/acknowledge")
    public ApiResponse<AlertEventResponse> acknowledge(@PathVariable Long id) {
        return ApiResponse.success(toResponse(alertEventService.acknowledge(id)));
    }

    @Operation(summary = "独立认领 / 接管告警", description = "记录认领人；如为 FIRING/SILENCED 自动转 ACK（规格 3.2）。需 ADMIN / OPERATOR")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "CLAIM_ALERT_EVENT", description = "认领告警事件")
    @PostMapping("/{id}/claim")
    public ApiResponse<AlertEventResponse> claim(@PathVariable Long id) {
        return ApiResponse.success(toResponse(alertEventService.claim(id)));
    }

    @Operation(summary = "批量确认告警", description = "跳过已恢复等不可确认事件（规格 2.7）。需 ADMIN / OPERATOR")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "ACK_ALERT_EVENT", description = "批量确认告警事件")
    @PostMapping("/batch-acknowledge")
    public ApiResponse<Map<String, Integer>> batchAcknowledge(@RequestBody @Valid BatchAckRequest request) {
        return ApiResponse.success(Map.of("count", alertEventService.batchAcknowledge(request.getIds())));
    }

    @Operation(summary = "批量静默告警", description = "跳过已恢复等不可静默事件（P1 维护窗口）。需 ADMIN / OPERATOR")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "SILENCE_ALERT_EVENT", description = "批量静默告警事件")
    @PostMapping("/batch-silence")
    public ApiResponse<Map<String, Integer>> batchSilence(@RequestBody @Valid BatchSilenceRequest request) {
        int count = alertEventService.batchSilence(request.getIds(), request.getReason(),
                request.getSilencedUntil(), request.getNote());
        int skipped = request.getIds().size() - count;
        return ApiResponse.success(Map.of("count", count, "skipped", skipped));
    }

    @Operation(summary = "告警汇总统计", description = "告警中心页顶部汇总卡数据（规格 2.3）：待处理 / 今日已触发 / 已诊断 / 平均诊断耗时")
    @GetMapping("/summary")
    public ApiResponse<AlertSummaryResponse> summary() {
        long openCount = alertEventMapper.selectCount(new LambdaQueryWrapper<AlertEventEntity>()
                .in(AlertEventEntity::getStatus,
                        AlertEventStatus.FIRING.name(), AlertEventStatus.ACKNOWLEDGED.name()));
        long firingCount = alertEventMapper.selectCount(new LambdaQueryWrapper<AlertEventEntity>()
                .eq(AlertEventEntity::getStatus, AlertEventStatus.FIRING.name()));
        LocalDate today = LocalDate.now();
        long todayCount = alertEventMapper.selectCount(new LambdaQueryWrapper<AlertEventEntity>()
                .ge(AlertEventEntity::getFiredAt, today.atStartOfDay()));
        long diagnosedCount = diagnosisRecordMapper.countDistinctSuccessEvents();
        Long avgDiagnoseMs = diagnosisRecordMapper.avgDiagnoseMs();

        AlertSummaryResponse resp = AlertSummaryResponse.builder()
                .openCount(openCount)
                .firingCount(firingCount)
                .todayCount(todayCount)
                .diagnosedCount(diagnosedCount)
                .avgDiagnoseMs(avgDiagnoseMs)
                .build();
        return ApiResponse.success(resp);
    }

    @Operation(summary = "手动恢复告警", description = "任意非 RESOLVED → RESOLVED，可带恢复备注（规格 2.4）。需 ADMIN / OPERATOR")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "RESOLVE_ALERT_EVENT", description = "手动恢复告警事件")
    @PostMapping("/{id}/resolve")
    public ApiResponse<AlertEventResponse> resolve(@PathVariable Long id,
                                                   @RequestBody(required = false) @Valid ResolveRequest request) {
        String note = request == null ? null : request.getResolutionNote();
        return ApiResponse.success(toResponse(alertEventService.resolve(id, note)));
    }

    @Operation(summary = "手动静默告警", description = "FIRING/SILENCED/ACK → SILENCED，带到期时间 + 原因（规格 2.5）。需 ADMIN / OPERATOR")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "SILENCE_ALERT_EVENT", description = "静默告警事件")
    @PostMapping("/{id}/silence")
    public ApiResponse<AlertEventResponse> silence(@PathVariable Long id,
                                                   @RequestBody @Valid SilenceRequest request) {
        return ApiResponse.success(toResponse(
                alertEventService.silence(id, request.getReason(), request.getSilencedUntil(), request.getNote())));
    }

    @Operation(summary = "解除静默", description = "SILENCED → 静默前状态（默认 FIRING），清除静默字段（规格 2.2）。需 ADMIN / OPERATOR")
    @RateLimit(key = "alertWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "UNSILENCE_ALERT_EVENT", description = "解除静默告警事件")
    @PostMapping("/{id}/unsilence")
    public ApiResponse<AlertEventResponse> unsilence(@PathVariable Long id) {
        return ApiResponse.success(toResponse(alertEventService.unsilence(id)));
    }

    private AlertEventResponse toResponse(AlertEventEntity entity) {
        return alertEventService.toResponse(entity);
    }
}
