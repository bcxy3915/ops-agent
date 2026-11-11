package com.example.opsaiagent.automation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.opsaiagent.automation.entity.AgentEvidenceEntity;
import com.example.opsaiagent.automation.entity.AgentStepEntity;
import com.example.opsaiagent.automation.entity.AgentTaskEntity;
import com.example.opsaiagent.automation.mapper.AgentEvidenceMapper;
import com.example.opsaiagent.automation.mapper.AgentStepMapper;
import com.example.opsaiagent.automation.mapper.AgentTaskMapper;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 通过 Spring 事务代理验证状态保护、证据契约和回滚触发，不替代真实 MySQL 验收。 */
class InspectionTaskPersistenceTest {
    private final AgentTaskMapper tasks = mock(AgentTaskMapper.class);
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final AgentEvidenceMapper evidence = mock(AgentEvidenceMapper.class);
    private final ObjectMapper mapper = spy(new ObjectMapper());
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private InspectionTaskPersistence persistence;
    private AgentTaskEntity task;
    private InspectionReport report;

    /**
     * 创建真实事务拦截代理及仅测试使用的任务夹具。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentStepEntity.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var proxy = new ProxyFactory(new InspectionTaskPersistence(tasks, steps, evidence, mapper));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        persistence = (InspectionTaskPersistence) proxy.getProxy();
        task = new AgentTaskEntity();
        task.setId("task-id");
        task.setTenantId("tenant-a");
        task.setServiceId("service-id");
        task.setExecutionToken("private-execution-token");
        report = new InspectionReport("service-id", "mall", 1000, 2000, "ABNORMAL", List.of("连接池等待"), List.of());
        when(tasks.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(steps.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        var step = new AgentStepEntity();
        step.setId("step-id");
        when(steps.selectOne(any(LambdaQueryWrapper.class))).thenReturn(step);
        when(evidence.insert(any(AgentEvidenceEntity.class))).thenReturn(1);
    }

    /**
     * 任务与步骤创建成功时提交，归属来自服务而非发起平台租户。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void createsTaskAndStepInOneTransaction() {
        when(tasks.insert(any(AgentTaskEntity.class))).thenReturn(1);
        when(steps.insert(any(AgentStepEntity.class))).thenReturn(1);
        OpsServiceEntity target = new OpsServiceEntity();
        target.setId("service-id");
        target.setTenantId("tenant-target");
        var result = persistence.begin(target, "0", "platform-user");
        assertThat(result.getTenantId()).isEqualTo("tenant-target");
        assertThat(result.getRequestTenantId()).isEqualTo("0");
        assertThat(result.getExecutionToken()).hasSize(32);
        verify(steps).insert(any(AgentStepEntity.class));
        verify(transactions).commit(any());
    }

    /**
     * 步骤建档失败时事务必须回滚任务建档。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void stepCreationFailureRollsBack() {
        when(tasks.insert(any(AgentTaskEntity.class))).thenReturn(1);
        when(steps.insert(any(AgentStepEntity.class))).thenReturn(0);
        assertThatThrownBy(() -> persistence.begin(new OpsServiceEntity(), "tenant", "user"))
                .isInstanceOf(BusinessException.class);
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    /**
     * 异常观测可以正常完成任务，执行状态与观测结论分别保存。
     * 无入参。
     * @return 无返回值
     * @throws Exception 结构化序列化失败
     */
    @Test
    void storesActualEvidenceAndGuardsTransition() throws Exception {
        persistence.complete(task, report);
        ArgumentCaptor<LambdaUpdateWrapper<AgentTaskEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(tasks).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSegment()).contains("tenant_id", "execution_token", "status");
        assertThat(update.getValue().getSqlSet()).contains("status", "outcome");
        assertThat(update.getValue().getParamNameValuePairs()).containsValue("SUCCEEDED").containsValue("ABNORMAL")
                .containsValue("private-execution-token");
        ArgumentCaptor<AgentEvidenceEntity> item = ArgumentCaptor.forClass(AgentEvidenceEntity.class);
        verify(evidence).insert(item.capture());
        assertThat(mapper.readValue(item.getValue().getPayload(), InspectionReport.class)).isEqualTo(report);
        assertThat(item.getValue().getPayload()).doesNotContain("private-execution-token");
        verify(transactions).commit(any());
    }

    /**
     * 晚到或重复完成不能追加证据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsStaleCompletionBeforeEvidenceInsert() {
        when(tasks.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> persistence.complete(task, report)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(evidence);
        verify(transactions).rollback(any());
    }

    /**
     * 证据写入失败触发回滚，不允许任务成功但证据丢失。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void evidenceFailureRollsBackCompletion() {
        when(evidence.insert(any(AgentEvidenceEntity.class))).thenThrow(new IllegalStateException("test database failure"));
        assertThatThrownBy(() -> persistence.complete(task, report)).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    /**
     * 受检异常同样回滚，验证 rollbackFor=Exception.class 实际经过代理生效。
     * 无入参。
     * @return 无返回值
     * @throws Exception 设置测试序列化行为失败
     */
    @Test
    void checkedSerializationFailureRollsBack() throws Exception {
        when(mapper.writeValueAsString(eq(report))).thenThrow(new JsonProcessingException("test checked failure") { });
        assertThatThrownBy(() -> persistence.complete(task, report)).isInstanceOf(JsonProcessingException.class);
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    /**
     * 失败状态必须同时写入任务与步骤，不存原始异常正文。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void marksTaskAndStepFailedWithoutPayload() {
        persistence.fail(task);
        verify(tasks).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(steps).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(evidence);
        verify(transactions).commit(any());
    }
}
