package com.example.opsaiagent.audit.task;

import com.example.opsaiagent.audit.config.AuditLogProperties;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogCleanupTask 单元测试
 * 覆盖：定时清理分批删除 / 无数据停止 / 异常捕获 / 批次休眠 / 手动清理 cleanNow
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计日志清理任务")
class AuditLogCleanupTaskTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private AuditLogProperties properties;

    @InjectMocks
    private AuditLogCleanupTask task;

    @BeforeEach
    void setUp() {
        when(properties.getRetentionDays()).thenReturn(30);
        when(properties.getBatchSize()).thenReturn(100);
    }

    // ==================== cleanup ====================

    @Test
    @DisplayName("cleanup：分批删除直到返回 0 为止")
    void cleanup_deleteBatches() {
        when(auditLogMapper.deleteBefore(any(), anyInt())).thenReturn(100, 50, 0);

        assertThatCode(() -> task.cleanup()).doesNotThrowAnyException();

        verify(auditLogMapper, times(3)).deleteBefore(any(), anyInt());
    }

    @Test
    @DisplayName("cleanup：无数据直接结束")
    void cleanup_noData() {
        when(auditLogMapper.deleteBefore(any(), anyInt())).thenReturn(0);

        task.cleanup();

        verify(auditLogMapper, times(1)).deleteBefore(any(), anyInt());
    }

    @Test
    @DisplayName("cleanup：数据库异常被捕获，不向外抛出")
    void cleanup_exceptionSwallowed() {
        when(auditLogMapper.deleteBefore(any(), anyInt()))
                .thenReturn(100)
                .thenThrow(new RuntimeException("DB error"));

        assertThatCode(() -> task.cleanup()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cleanup：批次间休眠后继续删除")
    void cleanup_sleepBetweenBatches() {
        when(properties.getBatchSleepMillis()).thenReturn(5L);
        when(auditLogMapper.deleteBefore(any(), anyInt())).thenReturn(100, 0);

        assertThatCode(() -> task.cleanup()).doesNotThrowAnyException();

        verify(auditLogMapper, times(2)).deleteBefore(any(), anyInt());
    }

    // ==================== cleanNow ====================

    @Test
    @DisplayName("cleanNow：返回累计删除条数")
    void cleanNow_returnsTotal() {
        when(auditLogMapper.deleteBefore(any(), anyInt())).thenReturn(100, 0);

        int total = task.cleanNow();

        assertThat(total).isEqualTo(100);
    }

    @Test
    @DisplayName("cleanNow：异常时不抛出并返回已删除条数")
    void cleanNow_exceptionReturnsPartial() {
        when(auditLogMapper.deleteBefore(any(), anyInt()))
                .thenReturn(50)
                .thenThrow(new RuntimeException("DB error"));

        int total = task.cleanNow();

        assertThat(total).isEqualTo(50);
    }
}
