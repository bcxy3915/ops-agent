package com.example.opsaiagent.maintenance.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维护窗口响应（P1 规格 5）
 * active = ended_at 为空且 until 晚于当前时间。
 */
@Data
@Builder
public class MaintenanceWindowResponse {

    private Long id;
    private String service;
    private String tenantId;
    private Long deptId;
    private String reason;
    private LocalDateTime until;
    private String note;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime endedAt;
    private boolean active;
}
