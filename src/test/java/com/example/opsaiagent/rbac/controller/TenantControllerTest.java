package com.example.opsaiagent.rbac.controller;

import com.example.opsaiagent.rbac.dto.TenantCreateRequest;
import com.example.opsaiagent.rbac.dto.TenantResponse;
import com.example.opsaiagent.rbac.dto.TenantUpdateRequest;
import com.example.opsaiagent.rbac.entity.TenantEntity;
import com.example.opsaiagent.rbac.service.TenantService;
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
 * TenantController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("租户管理接口")
class TenantControllerTest {

    @Mock
    private TenantService tenantService;

    @InjectMocks
    private TenantController controller;

    @Test
    @DisplayName("list：返回租户列表")
    void list() {
        when(tenantService.listAll()).thenReturn(List.of(tenant("t1")));

        assertThat(controller.list().getData()).hasSize(1);
    }

    @Test
    @DisplayName("create：返回创建后的租户")
    void create() {
        TenantCreateRequest request = new TenantCreateRequest();
        request.setCode("XH");
        request.setName("星海科技");
        when(tenantService.create(request)).thenReturn(tenant("t1"));

        TenantResponse response = controller.create(request).getData();

        assertThat(response.getId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("updateStatus：返回更新后的租户")
    void updateStatus() {
        TenantEntity suspended = tenant("t1");
        suspended.setStatus("SUSPENDED");
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setStatus("SUSPENDED");
        when(tenantService.updateStatus("t1", "SUSPENDED")).thenReturn(suspended);

        assertThat(controller.updateStatus("t1", request).getData().getStatus())
                .isEqualTo("SUSPENDED");
    }

    private TenantEntity tenant(String id) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setCode("XH");
        tenant.setName("星海科技");
        tenant.setStatus("ACTIVE");
        return tenant;
    }
}
