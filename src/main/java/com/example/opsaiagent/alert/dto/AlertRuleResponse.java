package com.example.opsaiagent.alert.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则响应
 */
@Data
@Builder
public class AlertRuleResponse {

    private Long id;
    private String name;
    private String description;
    private String service;
    private String metric;
    private String operator;
    private Double threshold;
    private String compareMode;
    private Integer durationSeconds;
    private Integer silenceSeconds;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
