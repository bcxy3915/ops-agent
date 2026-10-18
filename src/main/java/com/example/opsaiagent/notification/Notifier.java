package com.example.opsaiagent.notification;

import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;

/**
 * 通知渠道适配 SPI（T09）。
 * 实现类按渠道类型注册到 {@link NotifierRegistry}，发送失败抛异常由上层记录处理。
 */
public interface Notifier {

    /**
     * 该实现支持的渠道类型
     */
    NotificationChannelType type();

    /**
     * 发送一条通知
     *
     * @param channel 渠道配置（含 type 相关参数，如 webhookUrl / recipients）
     * @param content 渲染后的标题与正文
     * @throws Exception 发送失败时抛出，由通知中心负责重试与落库
     */
    void send(NotificationChannelConfigEntity channel, NotificationContent content) throws Exception;
}
