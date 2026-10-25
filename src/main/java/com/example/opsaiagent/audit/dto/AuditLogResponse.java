package com.example.opsaiagent.audit.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志响应（含操作中文名，供前端直接展示）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private Long id;

    private String username;

    private String tenantId;

    private Long deptId;

    /** 操作编码，如 UPLOAD_KNOWLEDGE */
    private String operation;

    /** 操作中文名，如 上传知识文档 */
    private String operationName;

    private String target;

    private String method;

    private String uri;

    private String params;

    private String result;

    private String errorMessage;

    private String ip;

    private String userAgent;

    private Long durationMs;

    private LocalDateTime createdAt;
}
