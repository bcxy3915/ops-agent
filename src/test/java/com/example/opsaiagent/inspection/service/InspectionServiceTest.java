package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.Observation;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.ops.mapper.OpsServiceMapper;
import com.example.opsaiagent.rbac.service.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证巡检的授权边界、规则结果及缺失证据语义。 */
class InspectionServiceTest {
    private final OpsServiceMapper mapper = mock(OpsServiceMapper.class);
    private final DataScope scope = mock(DataScope.class);
    private final InspectionCollector collector = mock(InspectionCollector.class);
    private final InspectionService inspection = new InspectionService(mapper, scope, collector);
    private OpsServiceEntity target;

    /**
     * 准备租户、部门和正常证据。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        target = new OpsServiceEntity();
        target.setId("service-id");
        target.setName("mall-order");
        target.setTenantId("tenant-a");
        target.setDeptId(10L);
        when(scope.tenantId()).thenReturn("tenant-a");
        when(scope.deptId()).thenReturn(10L);
        when(scope.visibleDeptIds()).thenReturn(List.of(10L));
        when(mapper.selectById("service-id")).thenReturn(target);
        when(collector.health(target)).thenReturn(new Observation("health", "AVAILABLE", "UP", 1));
        when(collector.metric(eq(target), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            double value = name.endsWith("max") ? 10.0 : 0.0;
            return new Observation(name, "AVAILABLE", value, 1);
        });
        ReflectionTestUtils.setField(inspection, "cpuThreshold", 0.9);
        ReflectionTestUtils.setField(inspection, "poolThreshold", 0.9);
    }

    /**
     * 正常证据生成正常报告，不改变登记服务状态。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void healthyReportContainsAllEvidenceWithoutWrites() {
        var report = inspection.inspect("service-id");
        assertThat(report.status()).isEqualTo("HEALTHY");
        assertThat(report.observations()).hasSize(5);
        assertThat(report.findings()).isEmpty();
        verify(mapper, never()).updateById(target);
    }

    /**
     * 连接池压力只生成观测事实，不伪称已定位慢 SQL。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void detectsPoolPressure() {
        when(collector.metric(target, "hikaricp.connections.active"))
                .thenReturn(new Observation("hikaricp.connections.active", "AVAILABLE", 9.0, 1));
        when(collector.metric(target, "hikaricp.connections.pending"))
                .thenReturn(new Observation("hikaricp.connections.pending", "AVAILABLE", 2.0, 1));
        var report = inspection.inspect("service-id");
        assertThat(report.status()).isEqualTo("ABNORMAL");
        assertThat(report.findings()).containsExactly("连接池使用率达到巡检阈值", "连接池存在等待请求");
    }

    /**
     * 缺少数据不能被解释为正常。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void missingEvidenceIsNotHealthy() {
        when(collector.health(target)).thenReturn(new Observation("health", "UNAVAILABLE", null, 1));
        assertThat(inspection.inspect("service-id").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    /**
     * 不完整的采集不能掩盖已证实异常。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void preservesConfirmedAbnormalityWithPartialEvidence() {
        when(collector.health(target)).thenReturn(new Observation("health", "AVAILABLE", "DOWN", 1));
        when(collector.metric(target, "process.cpu.usage"))
                .thenReturn(new Observation("process.cpu.usage", "UNAVAILABLE", null, 1));
        assertThat(inspection.inspect("service-id").status()).isEqualTo("ABNORMAL");
    }

    /**
     * 跨租户请求在网络访问之前拒绝。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsOtherTenantBeforeCollection() {
        target.setTenantId("tenant-b");
        assertThatThrownBy(() -> inspection.inspect("service-id")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(collector);
    }

    /**
     * 同租户不可见部门请求在网络访问之前拒绝。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsInvisibleDepartmentBeforeCollection() {
        target.setDeptId(20L);
        assertThatThrownBy(() -> inspection.inspect("service-id")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(collector);
    }

    /**
     * 无租户上下文不允许退化为全局访问。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsMissingTenantBeforeQuery() {
        when(scope.tenantId()).thenReturn(null);
        assertThatThrownBy(() -> inspection.inspect("service-id")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper, collector);
    }
}
