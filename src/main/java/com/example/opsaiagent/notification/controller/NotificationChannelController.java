package com.example.opsaiagent.notification.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.notification.dto.NotificationChannelRequest;
import com.example.opsaiagent.notification.dto.NotificationChannelResponse;
import com.example.opsaiagent.notification.dto.NotificationRecordResponse;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.service.NotificationChannelService;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
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
 * 通知渠道配置管理接口（T11）
 */
@Tag(name = "通知渠道", description = "通知渠道的增删改查与测试发送")
@RestController
@RequestMapping("/api/notifications/channels")
@RequiredArgsConstructor
public class NotificationChannelController {

    private final NotificationChannelService notificationChannelService;

    @Operation(summary = "查询通知渠道列表")
    @GetMapping
    public ApiResponse<List<NotificationChannelResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.success(notificationChannelService.list(type, enabled)
                .stream().map(this::toResponse).toList());
    }

    @Operation(summary = "创建通知渠道", description = "需要 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "notificationWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "CREATE_NOTIFICATION_CHANNEL", description = "创建通知渠道")
    @PostMapping
    public ApiResponse<NotificationChannelResponse> create(@Valid @RequestBody NotificationChannelRequest request) {
        return ApiResponse.success(toResponse(notificationChannelService.create(request)));
    }

    @Operation(summary = "更新通知渠道", description = "需要 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "notificationWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "UPDATE_NOTIFICATION_CHANNEL", description = "更新通知渠道")
    @PutMapping("/{id}")
    public ApiResponse<NotificationChannelResponse> update(@PathVariable Long id,
                                                           @Valid @RequestBody NotificationChannelRequest request) {
        return ApiResponse.success(toResponse(notificationChannelService.update(id, request)));
    }

    @Operation(summary = "启用 / 停用通知渠道", description = "需要 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "notificationWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "TOGGLE_NOTIFICATION_CHANNEL", description = "切换通知渠道启停")
    @PutMapping("/{id}/enabled")
    public ApiResponse<NotificationChannelResponse> setEnabled(@PathVariable Long id,
                                                               @RequestBody Map<String, Boolean> payload) {
        boolean enabled = payload == null || payload.getOrDefault("enabled", Boolean.TRUE);
        return ApiResponse.success(toResponse(notificationChannelService.setEnabled(id, enabled)));
    }

    @Operation(summary = "删除通知渠道", description = "需要 ADMIN 角色")
    @RateLimit(key = "notificationWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "DELETE_NOTIFICATION_CHANNEL", description = "删除通知渠道")
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        notificationChannelService.delete(id);
        return ApiResponse.success(Map.of("deleted", id));
    }

    @Operation(summary = "测试发送", description = "向渠道发送一条测试通知，返回发送记录")
    @RateLimit(key = "notificationWrite", limit = 10, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @PostMapping("/{id}/test")
    public ApiResponse<NotificationRecordResponse> test(@PathVariable Long id) {
        NotificationRecordEntity record = notificationChannelService.testSend(id);
        NotificationRecordResponse response = NotificationRecordResponse.builder().build();
        BeanUtils.copyProperties(record, response);
        return ApiResponse.success(response);
    }

    private NotificationChannelResponse toResponse(NotificationChannelConfigEntity entity) {
        NotificationChannelResponse response = NotificationChannelResponse.builder().build();
        BeanUtils.copyProperties(entity, response);
        return response;
    }
}
