package com.example.opsaiagent.notification.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.notification.dto.NotificationRecordResponse;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.mapper.NotificationRecordMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知发送记录查询接口（T10）
 */
@Tag(name = "通知记录", description = "通知发送记录查询")
@RestController
@RequestMapping("/api/notifications/records")
@RequiredArgsConstructor
public class NotificationRecordController {

    private final NotificationRecordMapper recordMapper;

    @Operation(summary = "查询通知发送记录", description = "可按状态 / 渠道 / 告警事件过滤")
    @GetMapping
    public ApiResponse<List<NotificationRecordResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long alertEventId,
            @RequestParam(defaultValue = "50") int limit) {
        LambdaQueryWrapper<NotificationRecordEntity> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(NotificationRecordEntity::getStatus, status);
        }
        if (channelId != null) {
            qw.eq(NotificationRecordEntity::getChannelId, channelId);
        }
        if (alertEventId != null) {
            qw.eq(NotificationRecordEntity::getAlertEventId, alertEventId);
        }
        qw.orderByDesc(NotificationRecordEntity::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        return ApiResponse.success(recordMapper.selectList(qw).stream().map(this::toResponse).toList());
    }

    private NotificationRecordResponse toResponse(NotificationRecordEntity entity) {
        NotificationRecordResponse response = NotificationRecordResponse.builder().build();
        BeanUtils.copyProperties(entity, response);
        return response;
    }
}
