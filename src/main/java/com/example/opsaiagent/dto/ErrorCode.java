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
    INTERNAL_ERROR(9999, "内部错误");

    private final int code;
    private final String message;
}