package com.example.opsaiagent.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知发送记录响应（T10 查看）
 */
@Data
@Builder
public class NotificationRecordResponse {

    private Long id;
    private Long alertEventId;
    private Long ruleId;
    private String ruleName;
    private String service;
    private String metric;
    private Long channelId;
    private String channelName;
    private String channelType;
    private String target;
    private String title;
    private String status;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime nextRetryAt;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
}
