package com.example.opsaiagent.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ops_audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String operation;

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