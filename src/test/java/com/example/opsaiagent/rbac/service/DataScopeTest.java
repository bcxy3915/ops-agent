package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DataScope 单元测试。
 *
 * 覆盖：当前租户/部门读取、可见部门子树、平台租户判断。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("数据范围")
class DataScopeTest {

    @Mock
    private DeptService deptService;

    @InjectMocks
    private DataScope dataScope;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        DeptContext.clear();
    }

    @Test
    @DisplayName("visibleDeptIds：返回当前部门子树")
    void visibleDeptIds() {
        TenantContext.set("t-default");
        DeptContext.set(5L);
        when(deptService.subtreeIds("t-default", 5L)).thenReturn(List.of(5L, 6L, 7L));

        assertThat(dataScope.tenantId()).isEqualTo("t-default");
        assertThat(dataScope.deptId()).isEqualTo(5L);
        assertThat(dataScope.visibleDeptIds()).containsExactly(5L, 6L, 7L);
    }

    @Test
    @DisplayName("visibleDeptIds：无上下文返回空列表")
    void visibleDeptIds_noContext() {
        assertThat(dataScope.visibleDeptIds()).isEmpty();
    }

    @Test
    @DisplayName("isPlatformTenant：保留租户 0")
    void isPlatformTenant() {
        TenantContext.set(TenantContext.PLATFORM_TENANT_ID);
        assertThat(dataScope.isPlatformTenant()).isTrue();

        TenantContext.set("t-default");
        assertThat(dataScope.isPlatformTenant()).isFalse();
    }
}
