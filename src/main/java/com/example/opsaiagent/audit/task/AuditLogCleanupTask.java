package com.example.opsaiagent.audit.task;

import com.example.opsaiagent.audit.config.AuditLogProperties;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计日志定时清理任务
 * 策略：
 * 1. 每天凌晨按 cron 执行一次
 * 2. 清理 created_at < NOW() - retentionDays 的记录
 * 3. 分批删除（每批 batchSize 条，间隔 batchSleepMillis 毫秒）
 *    避免一次 DELETE 锁表太久，影响业务写入
 * 4. 全程 try-catch，任何异常都不影响应用运行
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "ops-agent.audit.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AuditLogCleanupTask {

    private final AuditLogMapper auditLogMapper;
    private final AuditLogProperties properties;

    /**
     * 每天凌晨 3 点执行（可通过 ops-agent.audit.cron 配置）
     */
    @Scheduled(cron = "${ops-agent.audit.cron:0 0 3 * * ?}")
    public void cleanup() {
        LocalDateTime before = LocalDateTime.now().minusDays(properties.getRetentionDays());
        log.info("[审计清理] 开始，将删除 {} 之前的日志，批次大小 {}",
                before, properties.getBatchSize());

        long start = System.currentTimeMillis();
        int totalDeleted = 0;
        int batchCount = 0;

        try {
            while (true) {
                int deleted = auditLogMapper.deleteBefore(before, properties.getBatchSize());
                if (deleted == 0) break;

                totalDeleted += deleted;
                batchCount++;

                // 每批之间休眠，给数据库喘息
                if (properties.getBatchSleepMillis() > 0) {
                    Thread.sleep(properties.getBatchSleepMillis());
                }
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[审计清理] 完成：共删除 {} 条，{} 批，耗时 {} ms",
                    totalDeleted, batchCount, cost);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[审计清理] 被中断，已删除 {} 条", totalDeleted);
        } catch (Exception e) {
            log.error("[审计清理] 异常，已删除 {} 条", totalDeleted, e);
        }
    }

    /**
     * 提供手动触发接口（可选）：方便运维在需要时立即清理
     * 若不需要可以删掉这个方法
     */
    public int cleanNow() {
        LocalDateTime before = LocalDateTime.now().minusDays(properties.getRetentionDays());
        int total = 0;
        try {
            while (true) {
                int deleted = auditLogMapper.deleteBefore(before, properties.getBatchSize());
                if (deleted == 0) break;
                total += deleted;
                if (properties.getBatchSleepMillis() > 0) {
                    Thread.sleep(properties.getBatchSleepMillis());
                }
            }
        } catch (Exception e) {
            log.error("[审计清理] 手动触发异常", e);
        }
        return total;
    }
}