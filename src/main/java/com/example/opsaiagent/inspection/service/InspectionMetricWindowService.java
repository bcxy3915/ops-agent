package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.MetricWindowReport;
import com.example.opsaiagent.inspection.dto.MetricWindowReport.Window;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.store.MetricSampleStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 从 MySQL 获取相邻时间窗，显式标识缺口，供后续诊断工具复用。 */
@Service
@RequiredArgsConstructor
public class InspectionMetricWindowService {
    private static final int MAX_POINTS = 720;
    // 现有采样周期为 10 秒，允许三个周期的边界和点间间隔。
    private static final long MAX_GAP_MS = 30_000;
    private static final Map<String, String> METRICS = Map.of(
            "process.cpu.usage", "cpu", "hikaricp.connections.active", "hikariActive",
            "hikaricp.connections.max", "hikariMax", "hikaricp.connections.pending", "hikariPending");
    private final InspectionService inspectionService;
    private final MetricSampleStore samples;

    /**
     * 查询指定指标的当前窗口及紧邻基线窗口。
     * @param serviceId 已登记服务 ID
     * @param metric 白名单 Actuator 指标名
     * @param windowMinutes 单窗口长度，1～60 分钟
     * @param endMs 当前窗口终点，不能为空、超出当前时刻或早于两个完整窗口
     * @return 有界摘要及完整证据下的均值差
     */
    public MetricWindowReport compare(String serviceId, String metric, int windowMinutes, long endMs) {
        String storedMetric = METRICS.get(metric);
        long span = windowMinutes * 60_000L;
        if (storedMetric == null || windowMinutes < 1 || windowMinutes > 60
                || endMs > System.currentTimeMillis() || endMs < span * 2) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "仅支持白名单指标及 1～60 分钟的历史时间窗");
        }
        var service = inspectionService.requireVisibleService(serviceId);
        // 平台用户读取企业服务时，同样使用服务所属租户，不按平台租户或全局查询。
        if (service.getTenantId() == null || service.getTenantId().isBlank()) {
            throw new BusinessException(ErrorCode.TENANT_REQUIRED);
        }
        Window previous = window(service.getTenantId(), service.getName(), storedMetric,
                endMs - span * 2, endMs - span);
        Window current = window(service.getTenantId(), service.getName(), storedMetric,
                endMs - span, endMs);
        Double delta = "AVAILABLE".equals(previous.status()) && "AVAILABLE".equals(current.status())
                ? current.average() - previous.average() : null;
        return new MetricWindowReport(serviceId, metric, previous, current, delta);
    }

    /**
     * 查询并汇总单窗口；查多一行识别截断，不把局部数据冒充完整窗口。
     * @param tenantId 目标租户
     * @param service 服务名
     * @param metric 存储指标名
     * @param start 起点，包含
     * @param end 终点，不包含
     * @return 有质量标记的窗口摘要
     */
    private Window window(String tenantId, String service, String metric, long start, long end) {
        List<MetricPoint> rows = samples.queryBounded(tenantId, service, metric, start, end, MAX_POINTS + 1);
        if (rows.size() > MAX_POINTS) {
            return new Window(start, end, "TRUNCATED", rows.size(), 0, null, null, null, null);
        }
        // 多实例重复采样只保留同一时间戳的一点；重复和非法点仍使窗口标为不完整。
        Map<Long, Double> valid = new TreeMap<>();
        rows.stream().filter(point -> point != null && point.getTimestamp() >= start
                        && point.getTimestamp() < end && Double.isFinite(point.getValue())
                        && point.getValue() >= 0 && (!"cpu".equals(metric) || point.getValue() <= 1)
                        && (!"hikariMax".equals(metric) || point.getValue() > 0))
                .sorted(Comparator.comparingLong(MetricPoint::getTimestamp))
                .forEach(point -> valid.putIfAbsent(point.getTimestamp(), point.getValue()));
        if (valid.isEmpty()) {
            return new Window(start, end, "UNAVAILABLE", rows.size(), 0, null, null, null, null);
        }
        List<Long> times = List.copyOf(valid.keySet());
        long latest = times.getLast();
        boolean partial = valid.size() != rows.size() || times.getFirst() - start > MAX_GAP_MS
                || end - latest > MAX_GAP_MS;
        for (int i = 1; i < times.size(); i++) {
            partial |= times.get(i) - times.get(i - 1) > MAX_GAP_MS;
        }
        var summary = valid.values().stream().mapToDouble(Double::doubleValue).summaryStatistics();
        return new Window(start, end, partial ? "PARTIAL" : "AVAILABLE", rows.size(), valid.size(), latest,
                summary.getMin(), summary.getMax(), summary.getAverage());
    }
}
