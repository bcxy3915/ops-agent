package com.example.opsaiagent.automation.service;

import com.example.opsaiagent.automation.entity.AgentTaskEntity;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.inspection.service.InspectionService;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.rbac.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证建档、采集、结果提交的边界与失败记录，不启动外部服务。 */
class InspectionTaskServiceTest {
    private final InspectionService inspection = mock(InspectionService.class);
    private final InspectionTaskPersistence persistence = mock(InspectionTaskPersistence.class);
    private final AgentTaskQueryService queries = mock(AgentTaskQueryService.class);
    private final InspectionTaskService service = new InspectionTaskService(inspection, persistence, queries);
    private AgentTaskEntity task;
    private InspectionReport report;
    private OpsServiceEntity target;

    /**
     * 设置可信登录信息与实际采集报告夹具。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", "test", List.of()));
        target = new OpsServiceEntity();
        target.setId("svc");
        target.setTenantId("tenant-a");
        target.setDeptId(10L);
        task = new AgentTaskEntity();
        task.setId("task");
        when(inspection.requireVisibleService("svc")).thenReturn(target);
        when(persistence.begin(target, "tenant-a", "alice")).thenReturn(task);
        report = new InspectionReport("svc", "mall", 1, 2, "ABNORMAL", List.of(), List.of());
        when(inspection.inspect("svc")).thenReturn(report);
    }

    /**
     * 清理当前线程身份，避免测试间串租户。
     * 无入参。
     * @return 无返回值
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * 先提交建档，再采集，再保存证据，最后查询持久化结果。
     * 无入参。
     * @return 无返回值
     * @throws Exception 设置测试回调失败
     */
    @Test
    void persistsBeforeCollectionAndReturnsStoredDetail() throws Exception {
        service.run("svc");
        var order = inOrder(persistence, inspection, queries);
        order.verify(inspection).requireVisibleService("svc");
        order.verify(persistence).begin(target, "tenant-a", "alice");
        order.verify(inspection).inspect("svc");
        order.verify(inspection).requireVisibleService("svc");
        order.verify(persistence).complete(task, report);
        order.verify(queries).detail("task");
        verify(persistence, never()).fail(any());
    }

    /**
     * 建档失败时不能继续向目标服务发起采集。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void persistenceFailureBeforeStartStopsCollection() {
        when(persistence.begin(target, "tenant-a", "alice")).thenThrow(new IllegalStateException("test database unavailable"));
        assertThatThrownBy(() -> service.run("svc")).isInstanceOf(IllegalStateException.class);
        verify(inspection, never()).inspect(any());
        verifyNoInteractions(queries);
    }

    /**
     * 采集失败会记录任务失败，并给出可查询 ID，不回显原始异常。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void collectionFailureRecordsFailedTask() {
        when(inspection.inspect("svc")).thenThrow(new IllegalStateException("private token and URL"));
        assertThatThrownBy(() -> service.run("svc")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("task").hasMessageNotContaining("private token");
        verify(persistence).fail(task);
        verifyNoInteractions(queries);
    }

    /**
     * 采集期间目标归属变化时不将结果写入旧租户任务。
     * 无入参。
     * @return 无返回值
     * @throws Exception 验证持久化方法失败
     */
    @Test
    void changingTargetScopePreventsEvidenceWrite() throws Exception {
        var changed = new OpsServiceEntity();
        changed.setTenantId("tenant-b");
        when(inspection.requireVisibleService("svc")).thenReturn(target, changed);
        assertThatThrownBy(() -> service.run("svc")).isInstanceOf(BusinessException.class);
        verify(persistence, never()).complete(any(), any());
        verify(persistence).fail(task);
    }

    /**
     * 结果提交失败后尝试记录失败，不返回成功详情。
     * 无入参。
     * @return 无返回值
     * @throws Exception 设置持久化异常失败
     */
    @Test
    void evidenceCommitFailureDoesNotReturnSuccess() throws Exception {
        doThrow(new IllegalStateException("test commit failure")).when(persistence).complete(task, report);
        assertThatThrownBy(() -> service.run("svc")).isInstanceOf(BusinessException.class);
        verify(persistence).fail(task);
        verifyNoInteractions(queries);
    }
}
