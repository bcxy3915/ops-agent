package com.example.opsaiagent.audit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审计日志相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.audit")
public class AuditLogProperties {

    /**
     * 是否启用审计日志定时清理
     */
    private boolean cleanupEnabled = true;

    /**
     * 日志保留天数
     */
    private int retentionDays = 30;

    /**
     * 每批删除条数（避免长事务锁表）
     */
    private int batchSize = 1000;

    /**
     * 每批之间的休眠毫秒数
     */
    private long batchSleepMillis = 200;

    /**
     * 定时清理 cron 表达式
     */
    private String cron = "0 0 3 * * ?";
}