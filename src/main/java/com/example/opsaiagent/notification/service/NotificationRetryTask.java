package com.example.opsaiagent.notification.service;

import com.example.opsaiagent.notification.NotificationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知失败重试任务（T10）
 * 定时扫描 FAILED 且未达最大重试次数的记录，重新发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetryTask {

    private final NotificationCenter notificationCenter;
    private final NotificationProperties properties;

    @Scheduled(cron = "${ops-agent.notification.retry-cron:0 */5 * * * ?}")
    public void retry() {
        try {
            notificationCenter.retryPending(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("通知重试任务异常: {}", e.getMessage());
        }
    }
}
