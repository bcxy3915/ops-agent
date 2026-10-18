package com.example.opsaiagent.alert.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警事件响应
 */
@Data
@Builder
public class AlertEventResponse {

    private Long id;
    private Long ruleId;
    private String ruleName;
    private String service;
    private String metric;
    private String operator;
    private Double threshold;
    private Double currentValue;
    private String status;
    private String detail;
    private LocalDateTime firedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime updatedAt;
}
