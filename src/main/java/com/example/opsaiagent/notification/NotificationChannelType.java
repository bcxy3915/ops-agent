package com.example.opsaiagent.notification;

/**
 * 通知渠道类型（T09）
 */
public enum NotificationChannelType {
    /** 飞书自定义机器人 Webhook */
    FEISHU_WEBHOOK,
    /** 邮件（SMTP） */
    EMAIL,
    /** 通用 Webhook（POST JSON） */
    GENERIC_WEBHOOK
}
