package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.rbac.entity.TenantEntity;
import com.example.opsaiagent.rbac.mapper.TenantMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TenantService 单元测试。
 *
 * <p>覆盖：按 ID/编码查询 / 空参数 / 状态判断。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("租户服务")
class TenantServiceTest {

    @Mock
    private TenantMapper tenantMapper;

    @InjectMocks
    private TenantService tenantService;

    @Test
    @DisplayName("findById：空 ID 返回空")
    void findById_blank() {
        assertThat(tenantService.findById(null)).isEmpty();
        assertThat(tenantService.findById(" ")).isEmpty();
        verifyNoInteractions(tenantMapper);
    }

    @Test
    @DisplayName("findById：存在返回租户")
    void findById_found() {
        TenantEntity tenant = tenant("t-default", "ACTIVE");
        when(tenantMapper.selectById("t-default")).thenReturn(tenant);

        assertThat(tenantService.findById("t-default")).contains(tenant);
    }

    @Test
    @DisplayName("findByCode：按编码查询")
    void findByCode() {
        TenantEntity tenant = tenant("t-default", "ACTIVE");
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        assertThat(tenantService.findByCode("DEFAULT")).contains(tenant);
    }

    @Test
    @DisplayName("isActive：ACTIVE 返回 true")
    void isActive_true() {
        when(tenantMapper.selectById("t-default")).thenReturn(tenant("t-default", "ACTIVE"));

        assertThat(tenantService.isActive("t-default")).isTrue();
    }

    @Test
    @DisplayName("isActive：非 ACTIVE 返回 false")
    void isActive_false() {
        when(tenantMapper.selectById("t-default")).thenReturn(tenant("t-default", "SUSPENDED"));

        assertThat(tenantService.isActive("t-default")).isFalse();
    }

    @Test
    @DisplayName("isActive：租户不存在返回 false")
    void isActive_missing() {
        when(tenantMapper.selectById("none")).thenReturn(null);

        assertThat(tenantService.isActive("none")).isFalse();
    }

    private TenantEntity tenant(String id, String status) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setStatus(status);
        return tenant;
    }
}
