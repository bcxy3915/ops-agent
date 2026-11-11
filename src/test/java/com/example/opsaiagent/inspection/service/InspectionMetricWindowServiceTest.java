package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.common.error.ErrorCode;
import org.assertj.core.data.Offset;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.store.MetricSampleStore;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证相邻窗口、数据质量、查询预算及目标租户隔离。 */
class InspectionMetricWindowServiceTest {
    private static final long END = 1_700_000_000_000L;
    private final InspectionService scope = mock(InspectionService.class);
    private final MetricSampleStore store = mock(MetricSampleStore.class);
    private final InspectionMetricWindowService service = new InspectionMetricWindowService(scope, store);

    /**
     * 准备明确归属企业的服务与两个完整窗口。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        OpsServiceEntity target = new OpsServiceEntity();
        target.setId("id");
        target.setTenantId("tenant-target");
        target.setName("mall");
        when(scope.requireVisibleService("id")).thenReturn(target);
        when(store.queryBounded(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenAnswer(invocation -> points(invocation.getArgument(3), 0.2));
    }

    /**
     * 构造一分钟完整窗口，采样间隔为十秒。
     * @param start 窗口起点
     * @param value 测试指标值
     * @return 测试采样列表
     */
    private List<MetricPoint> points(long start, double value) {
        return IntStream.range(0, 6).mapToObj(i -> new MetricPoint(start + i * 10_000L, value)).toList();
    }

    /**
     * 只有完整的相邻窗口计算均值差，SQL 参数始终带目标租户。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void comparesAdjacentWindowsWithTargetTenant() {
        when(store.queryBounded("tenant-target", "mall", "cpu", END - 60_000, END, 721))
                .thenReturn(points(END - 60_000, 0.8));
        var result = service.compare("id", "process.cpu.usage", 1, END);
        assertThat(result.previous().status()).isEqualTo("AVAILABLE");
        assertThat(result.current().status()).isEqualTo("AVAILABLE");
        assertThat(result.averageDelta()).isCloseTo(0.6, Offset.offset(0.00001));
        verify(store).queryBounded("tenant-target", "mall", "cpu", END - 120_000, END - 60_000, 721);
    }

    /**
     * 缺失窗口不补零，不生成趋势差。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void emptyWindowDoesNotInventZero() {
        when(store.queryBounded("tenant-target", "mall", "cpu", END - 60_000, END, 721))
                .thenReturn(List.of());
        var result = service.compare("id", "process.cpu.usage", 1, END);
        assertThat(result.current().status()).isEqualTo("UNAVAILABLE");
        assertThat(result.current().average()).isNull();
        assertThat(result.averageDelta()).isNull();
    }

    /**
     * 中间缺口不能被窗口两端正常采样掩盖。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void gapPreventsCompleteComparison() {
        when(store.queryBounded("tenant-target", "mall", "cpu", END - 60_000, END, 721))
                .thenReturn(List.of(new MetricPoint(END - 60_000, 0.1), new MetricPoint(END - 1, 0.9)));
        var result = service.compare("id", "process.cpu.usage", 1, END);
        assertThat(result.current().status()).isEqualTo("PARTIAL");
        assertThat(result.averageDelta()).isNull();
    }

    /**
     * 非法值与重复点不参与重复加权，并标识不完整。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void invalidAndDuplicatePointsAreMarkedPartial() {
        List<MetricPoint> rows = new ArrayList<>(points(END - 60_000, 0.5));
        rows.add(new MetricPoint(END - 60_000, 0.9));
        rows.add(new MetricPoint(END - 1, Double.NaN));
        rows.add(new MetricPoint(END - 2, 2));
        when(store.queryBounded("tenant-target", "mall", "cpu", END - 60_000, END, 721)).thenReturn(rows);
        var result = service.compare("id", "process.cpu.usage", 1, END);
        assertThat(result.current().status()).isEqualTo("PARTIAL");
        assertThat(result.current().validCount()).isEqualTo(6);
        assertThat(result.current().average()).isEqualTo(0.5);
    }

    /**
     * 查询触达预算时不使用被截断的局部数据生成统计。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void reportsTruncationWithoutMisleadingStatistics() {
        when(store.queryBounded("tenant-target", "mall", "cpu", END - 60_000, END, 721))
                .thenReturn(IntStream.range(0, 721).mapToObj(i -> new MetricPoint(END - 60_000 + i, 0.2)).toList());
        var result = service.compare("id", "process.cpu.usage", 1, END);
        assertThat(result.current().status()).isEqualTo("TRUNCATED");
        assertThat(result.current().average()).isNull();
        assertThat(result.averageDelta()).isNull();
    }

    /**
     * 拒绝越权后不查询任何历史证据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void authorizationFailureStopsHistoryAccess() {
        doThrow(new BusinessException(ErrorCode.SERVICE_NOT_FOUND))
                .when(scope).requireVisibleService("id");
        assertThatThrownBy(() -> service.compare("id", "process.cpu.usage", 1, END))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(store);
    }

    /**
     * 参数超预算、未知指标或未来时间均在查询前拒绝。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> service.compare("id", "unknown", 1, END)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.compare("id", "process.cpu.usage", 61, END)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.compare("id", "process.cpu.usage", 1, Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(store);
    }
}
