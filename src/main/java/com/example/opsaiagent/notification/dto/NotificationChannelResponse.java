package com.example.opsaiagent.notification.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知渠道响应（T11）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationChannelResponse {

    private Long id;
    private String name;
    private String type;
    private Boolean enabled;
    private String config;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
