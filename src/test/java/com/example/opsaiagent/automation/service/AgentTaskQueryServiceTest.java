package com.example.opsaiagent.automation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.opsaiagent.automation.entity.AgentEvidenceEntity;
import com.example.opsaiagent.automation.entity.AgentStepEntity;
import com.example.opsaiagent.automation.entity.AgentTaskEntity;
import com.example.opsaiagent.automation.mapper.AgentEvidenceMapper;
import com.example.opsaiagent.automation.mapper.AgentStepMapper;
import com.example.opsaiagent.automation.mapper.AgentTaskMapper;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.inspection.service.InspectionService;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.rbac.service.DataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证任务详情回填、历史范围校验及目标租户隔离。 */
class AgentTaskQueryServiceTest {
    private final AgentTaskMapper tasks = mock(AgentTaskMapper.class);
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final AgentEvidenceMapper evidence = mock(AgentEvidenceMapper.class);
    private final InspectionService inspection = mock(InspectionService.class);
    private final DataScope scope = mock(DataScope.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentTaskQueryService service = new AgentTaskQueryService(tasks, steps, evidence, inspection, scope, mapper);

    /**
     * 初始化实体映射和可信租户上下文夹具。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentStepEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentEvidenceEntity.class);
        when(scope.tenantId()).thenReturn("tenant-a");
        when(scope.deptId()).thenReturn(10L);
        when(scope.visibleDeptIds()).thenReturn(List.of(10L, 11L));
    }

    /**
     * 新查询服务实例能从持久化快照还原报告，不依赖会话内存。
     * 无入参。
     * @return 无返回值
     * @throws Exception 测试序列化失败
     */
    @Test
    void readsStoredEvidenceAndHidesExecutionToken() throws Exception {
        var task = new AgentTaskEntity();
        task.setId("task");
        task.setTenantId("tenant-a");
        task.setServiceId("svc");
        task.setExecutionToken("never-expose-token");
        task.setStatus("SUCCEEDED");
        task.setOutcome("ABNORMAL");
        when(tasks.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        var target = new OpsServiceEntity();
        target.setTenantId("tenant-a");
        when(inspection.requireVisibleService("svc")).thenReturn(target);
        when(steps.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        var item = new AgentEvidenceEntity();
        item.setId("evidence");
        item.setEvidenceType("INSPECTION_REPORT");
        item.setPayload(mapper.writeValueAsString(new InspectionReport("svc", "mall", 1, 2,
                "ABNORMAL", List.of("连接池等待"), List.of())));
        when(evidence.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item));
        var result = service.detail("task");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.evidence().getFirst().report().status()).isEqualTo("ABNORMAL");
        assertThat(mapper.writeValueAsString(result)).doesNotContain("never-expose-token", "executionToken");
        ArgumentCaptor<LambdaQueryWrapper<AgentTaskEntity>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks).selectOne(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id", "dept_id");
        assertThat(query.getValue().getParamNameValuePairs()).containsValue("tenant-a");
    }

    /**
     * 无可信租户时不允许先查询全局任务。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void missingTenantDeniesDatabaseAccess() {
        when(scope.tenantId()).thenReturn(null);
        assertThatThrownBy(() -> service.detail("task")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(tasks, steps, evidence);
    }

    /**
     * 不可见任务不会继续读取关联证据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void invisibleTaskDeniesEvidenceAccess() {
        when(tasks.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.detail("hidden-task")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inspection, steps, evidence);
    }

    /**
     * 目标服务迁移租户后不允许继续读取原租户任务证据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void movedServiceDeniesHistoricalEvidence() {
        var task = new AgentTaskEntity();
        task.setTenantId("tenant-a");
        task.setServiceId("svc");
        when(tasks.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        var target = new OpsServiceEntity();
        target.setTenantId("tenant-b");
        when(inspection.requireVisibleService("svc")).thenReturn(target);
        assertThatThrownBy(() -> service.detail("task")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(steps, evidence);
    }

    /**
     * 平台查询也按目标企业限定，不能混入其他企业同名服务任务。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void platformListUsesTargetTenantAndLimit() {
        when(scope.tenantId()).thenReturn("0");
        when(scope.isPlatformTenant()).thenReturn(true);
        var target = new OpsServiceEntity();
        target.setTenantId("tenant-target");
        when(inspection.requireVisibleService("svc")).thenReturn(target);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        service.list("svc", 20);
        ArgumentCaptor<LambdaQueryWrapper<AgentTaskEntity>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id", "service_id", "LIMIT 20");
        assertThat(query.getValue().getParamNameValuePairs()).containsValue("tenant-target");
    }

    /**
     * 列表条数预算在查询前校验。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsOversizedList() {
        assertThatThrownBy(() -> service.list("svc", 51)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(tasks, inspection);
    }
}
