package com.example.opsaiagent.metrics.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.metrics.dto.MetricHistoryResponse;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.store.MetricSampleStore;
import com.example.opsaiagent.registry.ServiceRegistry;
import com.example.opsaiagent.security.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标采样历史查询（持久化数据源，区别于内存历史）。
 * 供告警引擎、故障快照、历史大盘使用。
 */
@Tag(name = "指标采样历史", description = "持久化指标采样查询")
@RestController
@RequestMapping("/api/metrics/samples")
@RequiredArgsConstructor
public class MetricSampleController {

    private final ServiceRegistry serviceRegistry;
    private final MetricSampleStore metricSampleStore;

    @Operation(summary = "查询指标采样历史", description = "按服务 + 指标（可选）+ 时间范围查询持久化采样数据")
    @GetMapping
    public ApiResponse<MetricHistoryResponse> query(
            @RequestParam String service,
            @RequestParam(required = false) String metric,
            @RequestParam(defaultValue = "1h") String range) {

        if (serviceRegistry.getByName(service) == null) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "服务不存在: " + service);
        }

        String effectiveRange = normalizeRange(range);
        long rangeMs = parseRange(effectiveRange);
        long endMs = System.currentTimeMillis();
        long startMs = endMs - rangeMs;

        Map<String, List<MetricPoint>> series = new LinkedHashMap<>();
        String tenantId = SecurityUtils.getCurrentTenantId();
        if (metric != null && !metric.isBlank()) {
            series.put(metric, metricSampleStore.query(tenantId, service, metric, startMs, endMs));
        } else {
            for (String m : List.of("cpu", "memory", "threadCount", "gcPause", "hikariActive")) {
                series.put(m, metricSampleStore.query(tenantId, service, m, startMs, endMs));
            }
        }

        return ApiResponse.success(MetricHistoryResponse.builder()
                .service(service)
                .range(effectiveRange)
                .series(series)
                .build());
    }

    private String normalizeRange(String range) {
        return switch (range) {
            case "5m", "15m", "1h", "6h", "1d" -> range;
            default -> "1h";
        };
    }

    private long parseRange(String range) {
        return switch (range) {
            case "5m" -> 5 * 60 * 1000L;
            case "15m" -> 15 * 60 * 1000L;
            case "6h" -> 6 * 60 * 60 * 1000L;
            case "1d" -> 24 * 60 * 60 * 1000L;
            default -> 60 * 60 * 1000L;
        };
    }
}
