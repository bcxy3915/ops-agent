package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.dto.TenantCreateRequest;
import com.example.opsaiagent.rbac.dto.TenantUpdateRequest;
import com.example.opsaiagent.rbac.entity.TenantEntity;
import com.example.opsaiagent.rbac.mapper.TenantMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TenantService 单元测试。
 *
 * 覆盖：按 ID/编码查询 / 空参数 / 状态判断。
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

    @Test
    @DisplayName("create：生成 ID 并默认启用")
    void create_success() {
        when(tenantMapper.selectOne(any())).thenReturn(null);
        TenantCreateRequest request = new TenantCreateRequest();
        request.setCode("XH");
        request.setName("星海科技");

        TenantEntity created = tenantService.create(request);

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getCode()).isEqualTo("XH");
        assertThat(created.getName()).isEqualTo("星海科技");
        assertThat(created.getStatus()).isEqualTo(TenantService.STATUS_ACTIVE);
        assertThat(created.getKnowledgeReviewMode()).isEqualTo("DEPT_ONLY");
        verify(tenantMapper).insert(created);
    }

    @Test
    @DisplayName("create：编码重复抛业务异常")
    void create_duplicateCode() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant("t1", "ACTIVE"));
        TenantCreateRequest request = new TenantCreateRequest();
        request.setCode("XH");
        request.setName("星海科技");

        assertThatThrownBy(() -> tenantService.create(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TENANT_CODE_EXISTS);
    }

    @Test
    @DisplayName("update：更新名称/审核模式/状态")
    void update_success() {
        when(tenantMapper.selectById("t1")).thenReturn(tenant("t1", "ACTIVE"));
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setName("新名称");
        request.setKnowledgeReviewMode("DEPT_AND_TENANT");
        request.setStatus("SUSPENDED");

        TenantEntity updated = tenantService.update("t1", request);

        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getKnowledgeReviewMode()).isEqualTo("DEPT_AND_TENANT");
        assertThat(updated.getStatus()).isEqualTo("SUSPENDED");
        verify(tenantMapper).updateById(updated);
    }

    @Test
    @DisplayName("updateStatus：非法状态抛业务异常")
    void updateStatus_invalid() {
        assertThatThrownBy(() -> tenantService.updateStatus("t1", "UNKNOWN"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("listAll：返回全部租户")
    void listAll_success() {
        when(tenantMapper.selectList(any())).thenReturn(List.of(tenant("t1", "ACTIVE")));

        assertThat(tenantService.listAll()).hasSize(1);
    }

    private TenantEntity tenant(String id, String status) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setStatus(status);
        return tenant;
    }
}
