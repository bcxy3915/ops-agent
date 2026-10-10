package com.example.opsaiagent.audit.aspect;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.service.AuditLogService;
import com.example.opsaiagent.security.dto.LoginRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogAspect 单元测试
 * 纯 Mockito，不启 Spring 上下文。
 * 通过 mock ProceedingJoinPoint + AuditLog 注解驱动切面。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计日志切面")
class AuditLogAspectTest {

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private AuditLogAspect aspect;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        aspect = new AuditLogAspect(auditLogService, objectMapper);
        SecurityContextHolder.clearContext();

        lenient().when(auditLog.operation()).thenReturn("TEST_OP");
        lenient().when(auditLog.recordParams()).thenReturn(true);
        lenient().when(joinPoint.getSignature()).thenReturn(methodSignature);
    }

    // ==================== 成功 ====================

    @Test
    @DisplayName("成功：记录 SUCCESS 和耗时")
    void around_success() throws Throwable {
        when(methodSignature.getParameterNames()).thenReturn(new String[]{"serviceName"});
        when(joinPoint.getArgs()).thenReturn(new Object[]{"todo-service"});
        when(joinPoint.proceed()).thenReturn("OK");

        Object result = aspect.around(joinPoint, auditLog);

        assertThat(result).isEqualTo("OK");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getOperation()).isEqualTo("TEST_OP");
        assertThat(saved.getResult()).isEqualTo("SUCCESS");
        assertThat(saved.getTarget()).isEqualTo("todo-service");
        assertThat(saved.getDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(saved.getErrorMessage()).isNull();
    }

    // ==================== 失败 ====================

    @Test
    @DisplayName("异常：记录 FAILURE 和错误消息")
    void around_failure() throws Throwable {
        when(methodSignature.getParameterNames()).thenReturn(new String[]{"serviceName"});
        when(joinPoint.getArgs()).thenReturn(new Object[]{"svc"});
        when(joinPoint.proceed()).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> aspect.around(joinPoint, auditLog))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getResult()).isEqualTo("FAILURE");
        assertThat(saved.getErrorMessage()).isEqualTo("boom");
    }

    // ==================== 参数脱敏 ====================

    @Test
    @DisplayName("参数脱敏：password 被替换为 ******")
    void around_sensitiveParamsMasked() throws Throwable {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("admin123");

        when(methodSignature.getParameterNames()).thenReturn(new String[]{"request"});
        when(joinPoint.getArgs()).thenReturn(new Object[]{req});
        when(joinPoint.proceed()).thenReturn("OK");

        aspect.around(joinPoint, auditLog);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).save(captor.capture());

        String params = captor.getValue().getParams();
        assertThat(params).contains("admin");
        assertThat(params).doesNotContain("admin123");
        assertThat(params).contains("******");
    }

    // ==================== recordParams = false ====================

    @Test
    @DisplayName("recordParams=false：不记录参数")
    void around_recordParamsFalse() throws Throwable {
        when(auditLog.recordParams()).thenReturn(false);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"x"});
        when(joinPoint.proceed()).thenReturn("OK");

        aspect.around(joinPoint, auditLog);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).save(captor.capture());
        assertThat(captor.getValue().getParams()).isNull();
    }

    // ==================== 空参数 ====================

    @Test
    @DisplayName("空参数：不抛异常")
    void around_emptyArgs() throws Throwable {
        when(methodSignature.getParameterNames()).thenReturn(new String[]{"serviceName"});
        when(joinPoint.getArgs()).thenReturn(new Object[]{null});
        when(joinPoint.proceed()).thenReturn("OK");

        Object result = aspect.around(joinPoint, auditLog);

        assertThat(result).isEqualTo("OK");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).save(captor.capture());
    }
}