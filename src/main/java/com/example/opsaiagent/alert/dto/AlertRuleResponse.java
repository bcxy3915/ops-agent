package com.example.opsaiagent.alert.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleResponse {

    private Long id;
    private String name;
    private String tenantId;
    private Long deptId;
    private String description;
    private String service;
    private String metric;
    private String operator;
    private Double threshold;
    private String compareMode;
    private Integer durationSeconds;
    private Integer silenceSeconds;
    private Boolean enabled;

    /** 升级策略 */
    private Boolean escalationEnabled;
    private Integer escalationIntervalSeconds;
    private Integer escalationMaxLevel;
    private List<AlertRuleLevelDto> escalationLevels;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
