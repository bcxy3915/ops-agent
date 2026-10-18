package com.example.opsaiagent.metrics.task;

import com.example.opsaiagent.metrics.store.MetricSampleStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 指标采样数据保留策略清理任务。
 * 默认保留 7 天，防止 metric_sample 表无限增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricSampleCleanupTask {

    private final MetricSampleStore metricSampleStore;

    /**
     * 保留天数
     */
    @Value("${ops-agent.metrics.retention-days:7}")
    private int retentionDays;

    /**
     * 每天凌晨 2:30 清理过期采样数据
     */
    @Scheduled(cron = "${ops-agent.metrics.cleanup-cron:0 30 2 * * ?}")
    public void cleanup() {
        long beforeMs = System.currentTimeMillis() - Duration.ofDays(Math.max(1, retentionDays)).toMillis();
        try {
            int deleted = metricSampleStore.deleteOlderThan(beforeMs);
            log.info("指标采样数据清理完成，删除 {} 条（保留 {} 天）", deleted, retentionDays);
        } catch (Exception e) {
            log.warn("指标采样数据清理失败: {}", e.getMessage());
        }
    }
}
