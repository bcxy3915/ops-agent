package com.example.opsaiagent.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.metrics.MetricSampler;
import com.example.opsaiagent.metrics.dto.MetricHistoryResponse;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.dto.MetricSnapshotResponse;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "指标监控", description = "服务指标快照与历史查询")
@RestController
@RequestMapping("/api/services/{serviceName}/metrics")
@RequiredArgsConstructor
public class MetricController {

    private final ServiceRegistry serviceRegistry;
    private final MetricSampler metricSampler;

    @Operation(summary = "指标快照", description = "获取服务的实时指标快照")
    @GetMapping("/snapshot")
    public ApiResponse<MetricSnapshotResponse> snapshot(@PathVariable String serviceName) {
        ServiceInfo service = serviceRegistry.getByName(serviceName);
        if (service == null) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "服务不存在: " + serviceName);
        }

        MetricSnapshotResponse.Snapshot snapshot = metricSampler.getLatestSnapshot(serviceName);
        if (snapshot == null) {
            // 应用刚启动时还没采样，返回全 0
            snapshot = MetricSnapshotResponse.Snapshot.builder().build();
        }

        return ApiResponse.success(MetricSnapshotResponse.builder()
                .service(serviceName)
                .timestamp(System.currentTimeMillis())
                .snapshot(snapshot)
                .build());
    }

    @Operation(summary = "指标历史", description = "获取指定时间范围内的时序数据")
    @GetMapping("/history")
    public ApiResponse<MetricHistoryResponse> history(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "5m") String range) {

        ServiceInfo service = serviceRegistry.getByName(serviceName);
        if (service == null) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "服务不存在: " + serviceName);
        }

        long rangeMs = parseRange(range);
        long endMs = System.currentTimeMillis();
        long startMs = endMs - rangeMs;

        Map<String, List<MetricPoint>> series = new LinkedHashMap<>();
        for (String metric : List.of("cpu", "memory", "threadCount", "gcPause", "hikariActive")) {
            series.put(metric, metricSampler.getHistory(serviceName, metric, startMs, endMs));
        }

        return ApiResponse.success(MetricHistoryResponse.builder()
                .service(serviceName)
                .range(range)
                .series(series)
                .build());
    }

    /**
     * 把 range 字符串转成毫秒
     * @param range 5m, 15m, 1h, 6h
     * @return 毫秒
     */
    private long parseRange(String range) {
        return switch (range) {
            case "5m" -> 5 * 60 * 1000L;
            case "15m" -> 15 * 60 * 1000L;
            case "1h" -> 60 * 60 * 1000L;
            case "6h" -> 6 * 60 * 60 * 1000L;
            default -> 5 * 60 * 1000L;
        };
    }
}