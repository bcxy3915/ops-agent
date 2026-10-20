package com.example.opsaiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    SERVICE_NOT_FOUND(1001, "服务不存在"),
    SERVICE_ALREADY_EXISTS(1002, "服务已存在"),
    INVALID_PARAMETER(1003, "参数不合法"),
    HEALTH_CHECK_FAILED(1004, "健康检查失败"),
    USER_ALREADY_EXISTS(1005, "用户名已存在"),
    ALERT_RULE_NOT_FOUND(1006, "告警规则不存在"),
    ALERT_RULE_NAME_EXISTS(1007, "告警规则名称已存在"),
    ALERT_RULE_INVALID(1008, "告警规则参数不合法"),
    ALERT_EVENT_NOT_FOUND(1009, "告警事件不存在"),
    NOTIFICATION_CHANNEL_NOT_FOUND(1010, "通知渠道不存在"),
    NOTIFICATION_CHANNEL_NAME_EXISTS(1011, "通知渠道名称已存在"),
    NOTIFICATION_CHANNEL_INVALID(1012, "通知渠道参数不合法"),
    SNAPSHOT_NOT_FOUND(1013, "故障快照不存在"),
    DIAGNOSIS_NOT_FOUND(1014, "诊断记录不存在"),
    INTERNAL_ERROR(9999, "内部错误"),
    KNOWLEDGE_DOCUMENT_NOT_FOUND(1015, "知识文档不存在"),
    KNOWLEDGE_DOCUMENT_INVALID(1016, "知识文档参数不合法"),
    KNOWLEDGE_CASE_NOT_FOUND(1017, "知识案例不存在"),
    KNOWLEDGE_CASE_INVALID(1018, "知识案例参数不合法"),
    KNOWLEDGE_UPLOAD_FAILED(1019, "知识文档上传失败"),
    KNOWLEDGE_RELOAD_FAILED(1020, "知识库重载失败"),
    KNOWLEDGE_CONFLICT_NOT_FOUND(1021, "知识冲突记录不存在"),
    KNOWLEDGE_CONFLICT_INVALID(1022, "知识冲突裁定参数不合法"),
    KNOWLEDGE_VERSION_NOT_FOUND(1023, "知识文档版本不存在"),
    USER_NOT_FOUND(1024, "用户不存在"),
    USER_OPERATION_FORBIDDEN(1025, "禁止对该用户执行该操作"),
    ALERT_EVENT_INVALID(1026, "告警事件操作非法"),
    MAINTENANCE_WINDOW_NOT_FOUND(1027, "维护窗口不存在");

    private final int code;
    private final String message;
}