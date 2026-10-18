package com.example.opsaiagent.audit.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.audit.dto.AuditLogResponse;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogController 单元测试
 * 覆盖：分页查询的筛选参数透传、操作中文名（operationName）映射
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计日志接口")
class AuditLogControllerTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AuditLogController controller;

    private AuditLogEntity entity(Long id, String username, String operation) {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(id);
        e.setUsername(username);
        e.setOperation(operation);
        e.setResult("SUCCESS");
        return e;
    }

    @Test
    @DisplayName("list：无筛选参数，走默认分页")
    void list_noFilter() {
        Page<AuditLogEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(entity(1L, "admin", "LOGIN")));
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        ApiResponse<IPage<AuditLogResponse>> result = controller.list(null, null, null, 1, 20);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords()).hasSize(1);
        assertThat(result.getData().getRecords().get(0).getOperationName()).isEqualTo("登录系统");

        ArgumentCaptor<Page<AuditLogEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("list：知识库操作映射为中文")
    void list_knowledgeOperationMapped() {
        Page<AuditLogEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(
                entity(1L, "admin", "UPLOAD_KNOWLEDGE"),
                entity(2L, "admin", "PUBLISH_KNOWLEDGE"),
                entity(3L, "admin", "CONFIRM_CASE"),
                entity(4L, "admin", "RELOAD_KNOWLEDGE")
        ));
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        ApiResponse<IPage<AuditLogResponse>> result = controller.list(null, null, null, 1, 10);

        List<AuditLogResponse> records = result.getData().getRecords();
        assertThat(records.get(0).getOperationName()).isEqualTo("上传知识文档");
        assertThat(records.get(1).getOperationName()).isEqualTo("审核发布知识文档");
        assertThat(records.get(2).getOperationName()).isEqualTo("确认沉淀故障案例");
        assertThat(records.get(3).getOperationName()).isEqualTo("重建知识库索引");
        // 原操作编码保留
        assertThat(records.get(0).getOperation()).isEqualTo("UPLOAD_KNOWLEDGE");
    }

    @Test
    @DisplayName("list：未知操作编码原样返回")
    void list_unknownOperationFallback() {
        Page<AuditLogEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(entity(1L, "admin", "CUSTOM_OP")));
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        ApiResponse<IPage<AuditLogResponse>> result = controller.list(null, null, null, 1, 10);

        assertThat(result.getData().getRecords().get(0).getOperationName()).isEqualTo("CUSTOM_OP");
    }

    @Test
    @DisplayName("list：带全部筛选参数")
    void list_withFilters() {
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

        ApiResponse<IPage<AuditLogResponse>> result =
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

        ApiResponse<IPage<AuditLogResponse>> result = controller.list(null, "CREATE_USER", null, 1, 50);

        assertThat(result.getData()).isNotNull();
        ArgumentCaptor<Page<AuditLogEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(50);
    }
}
