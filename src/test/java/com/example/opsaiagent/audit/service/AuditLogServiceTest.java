package com.example.opsaiagent.audit.service;

import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AuditLogService 单元测试
 * 覆盖：异步保存审计日志 / 数据库异常被内部消化
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计日志服务")
class AuditLogServiceTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AuditLogService auditLogService;

    private AuditLogEntity log(String username, String operation) {
        AuditLogEntity e = new AuditLogEntity();
        e.setUsername(username);
        e.setOperation(operation);
        e.setResult("SUCCESS");
        return e;
    }

    @Test
    @DisplayName("save：正常插入审计日志")
    void save_success() {
        AuditLogEntity entity = log("admin", "LOGIN");

        auditLogService.save(entity);

        verify(auditLogMapper).insert(entity);
    }

    @Test
    @DisplayName("save：数据库异常被捕获，不向外抛出")
    void save_exceptionSwallowed() {
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(auditLogMapper).insert(any(AuditLogEntity.class));

        assertThatCode(() -> auditLogService.save(log("admin", "LOGIN")))
                .doesNotThrowAnyException();

        verify(auditLogMapper).insert(any(AuditLogEntity.class));
    }

    @Test
    @DisplayName("save：null 实体时 mapper 不调用（防御性）")
    void save_nullEntity_noInsert() {
        auditLogService.save(null);

        verify(auditLogMapper, never()).insert(any(AuditLogEntity.class));
    }
}
