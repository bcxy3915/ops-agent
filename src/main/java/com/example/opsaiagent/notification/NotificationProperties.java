package com.example.opsaiagent.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通知模块配置（ops-agent.notification.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.notification")
public class NotificationProperties {

    /** 单条通知最大重试次数 */
    private int maxRetry = 2;

    /** 失败后首次重试延迟（秒） */
    private int retryDelaySec = 300;

    /** 重试任务 cron（默认每 5 分钟） */
    private String retryCron = "0 */5 * * * ?";
}
