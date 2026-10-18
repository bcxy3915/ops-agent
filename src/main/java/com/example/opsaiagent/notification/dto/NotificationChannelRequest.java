package com.example.opsaiagent.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通知渠道创建 / 更新请求（T11）
 */
@Data
public class NotificationChannelRequest {

    @NotBlank(message = "渠道名称不能为空")
    private String name;

    /** FEISHU_WEBHOOK / EMAIL / GENERIC_WEBHOOK */
    @NotBlank(message = "渠道类型不能为空")
    private String type;

    /** 是否启用 */
    private Boolean enabled = true;

    /** 渠道参数 JSON（webhookUrl / recipients / subjectPrefix / secret） */
    private String config;

    private String description;
}
