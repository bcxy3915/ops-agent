package com.example.opsaiagent.audit.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import com.example.opsaiagent.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogController 单元测试
 * 覆盖：分页查询的筛选参数透传
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计日志接口")
class AuditLogControllerTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AuditLogController controller;

    @Test
    @DisplayName("list：无筛选参数，走默认分页")
    void list_noFilter() {
        Page<AuditLogEntity> page = new Page<>();
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        ApiResponse<Page<AuditLogEntity>> result = controller.list(null, null, null, 1, 20);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isSameAs(page);

        ArgumentCaptor<Page<AuditLogEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("list：带全部筛选参数")
    void list_withFilters() {
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

        ApiResponse<Page<AuditLogEntity>> result =
                controller.list("admin", "LOGIN", "SUCCESS", 2, 10);

        assertThat(result.getData()).isNotNull();
        ArgumentCaptor<Page<AuditLogEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("list：部分筛选参数")
    void list_partialFilters() {
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

        ApiResponse<Page<AuditLogEntity>> result = controller.list(null, "CREATE_USER", null, 1, 50);

        assertThat(result.getData()).isNotNull();
        ArgumentCaptor<Page<AuditLogEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(50);
    }
}
