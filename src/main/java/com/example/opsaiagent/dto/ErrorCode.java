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
    INTERNAL_ERROR(9999, "内部错误");

    private final int code;
    private final String message;
}