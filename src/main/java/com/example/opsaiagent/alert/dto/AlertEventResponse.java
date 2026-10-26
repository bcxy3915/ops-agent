package com.example.opsaiagent.alert.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 告警事件响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEventResponse {

    private Long id;
    private Long ruleId;
    private String tenantId;
    private Long deptId;
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

    /** 认领/确认人 */
    private String acknowledgedBy;

    /** 认领/确认时间 */
    private LocalDateTime acknowledgedAt;

    /** 恢复操作人 */
    private String resolvedBy;

    /** 恢复备注 */
    private String resolutionNote;

    /** 手动静默到期时间 */
    private LocalDateTime silencedUntil;

    /** 静默原因 */
    private String silencedReason;

    /** 静默操作人 */
    private String silencedBy;

    /** 静默操作时间（处置时间线，规格 3.5） */
    private LocalDateTime silencedAt;

    /** 静默前状态（到期/解除后回原状态） */
    private String silencedFrom;

    /** 静默备注 */
    private String silencedNote;

    /** 升级级别（0=未升级） */
    private Integer escalationLevel;

    /** 最近升级时间 */
    private LocalDateTime escalatedAt;

    /** 当前登录用户是否已读（未登录为 null） */
    private Boolean read;
}
