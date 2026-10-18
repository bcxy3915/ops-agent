package com.example.opsaiagent.service;

import com.example.opsaiagent.entity.OpsServiceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ScheduledHealthChecker 单元测试
 * 覆盖：空服务列表直接返回 / 批量检查统计 UP 与 DOWN / 单服务异常不中断
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("定时健康检查")
class ScheduledHealthCheckerTest {

    @Mock
    private OpsServiceManager serviceManager;

    @Mock
    private ServiceHealthChecker healthChecker;

    @InjectMocks
    private ScheduledHealthChecker checker;

    private OpsServiceEntity svc(String name) {
        OpsServiceEntity e = new OpsServiceEntity();
        e.setId("id-" + name);
        e.setName(name);
        return e;
    }

    @Test
    @DisplayName("checkAllServices：无服务时不做任何检查")
    void checkAll_emptyServices() {
        when(serviceManager.listAll()).thenReturn(List.of());

        checker.checkAllServices();

        verifyNoInteractions(healthChecker);
    }

    @Test
    @DisplayName("checkAllServices：逐个检查并统计 UP/DOWN")
    void checkAll_countsUpAndDown() {
        when(serviceManager.listAll()).thenReturn(List.of(svc("a"), svc("b"), svc("c")));
        when(healthChecker.checkNow("a")).thenReturn("UP");
        when(healthChecker.checkNow("b")).thenReturn("DOWN");
        when(healthChecker.checkNow("c")).thenReturn("UP");

        checker.checkAllServices();

        verify(healthChecker, times(3)).checkNow(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("checkAllServices：单服务异常不中断批量检查")
    void checkAll_serviceExceptionContinues() {
        when(serviceManager.listAll()).thenReturn(List.of(svc("a"), svc("b")));
        when(healthChecker.checkNow("a")).thenThrow(new RuntimeException("boom"));
        when(healthChecker.checkNow("b")).thenReturn("UP");

        checker.checkAllServices();

        verify(healthChecker, times(2)).checkNow(org.mockito.ArgumentMatchers.anyString());
    }
}
