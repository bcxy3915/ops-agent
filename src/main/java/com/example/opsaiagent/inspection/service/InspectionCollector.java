package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.inspection.dto.Observation;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.resilience.ResilienceProperties;
import com.example.opsaiagent.resilience.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Set;

/** 确定性只读采集器，仅访问已登记服务的健康和白名单指标。 */
@Component
public class InspectionCollector {
    private static final Set<String> METRICS = Set.of("process.cpu.usage",
            "hikaricp.connections.active", "hikaricp.connections.max",
            "hikaricp.connections.pending");
    private final ObjectMapper mapper;
    private final RestClient client;

    /**
     * 使用项目超时配置创建采集器。
     * @param mapper JSON 映射器
     * @param properties 外部调用超时配置
     * @return 无返回值
     */
    @Autowired
    public InspectionCollector(ObjectMapper mapper, ResilienceProperties properties) {
        this(mapper, RestClientFactory.builder(properties).build());
    }

    /**
     * 使用指定客户端创建采集器，供无网络契约测试复用。
     * @param mapper JSON 映射器
     * @param client 只读 HTTP 客户端
     * @return 无返回值
     */
    InspectionCollector(ObjectMapper mapper, RestClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    /**
     * 查询健康端点；仅认可明确的状态，不将连接失败解释成 DOWN。
     * @param service 已授权服务
     * @return 结构化健康证据
     */
    public Observation health(OpsServiceEntity service) {
        return collect(service, "health", service.getHealthPath(), true);
    }

    /**
     * 查询单项白名单指标，保留“缺失”和数值零的区别。
     * @param service 已授权服务
     * @param metric 白名单指标名
     * @return 结构化指标证据
     */
    public Observation metric(OpsServiceEntity service, String metric) {
        if (!METRICS.contains(metric)) {
            throw new IllegalArgumentException("不支持的巡检指标");
        }
        String path = service.getMetricsPath();
        if (path != null) {
            path = path.replaceAll("/+$", "") + "/" + metric;
        }
        return collect(service, metric, path, false);
    }

    /**
     * 读取并约束响应，只返回健康状态或有限数值；错误不回显地址与凭据。
     * @param service 已授权服务
     * @param name 证据名称
     * @param path 已登记端点路径
     * @param health 是否解析健康状态
     * @return 本次采集证据
     */
    private Observation collect(OpsServiceEntity service, String name, String path, boolean health) {
        try {
            URI base = URI.create(service.getBaseUrl());
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null
                    || path == null || !path.startsWith("/") || path.startsWith("//")
                    || path.contains("?") || path.contains("#") || path.contains("..")) {
                return evidence(name, "INVALID", null);
            }
            RestClient.RequestHeadersSpec<?> request = client.get()
                    .uri(service.getBaseUrl().replaceAll("/+$", "") + path);
            if (service.getAuthToken() != null && !service.getAuthToken().isBlank()) {
                request.headers(headers -> headers.setBearerAuth(service.getAuthToken()));
            }
            // Actuator 的 DOWN 通常返回 503，仍需读取明确的健康状态。
            String body = request.retrieve().onStatus(code -> health && code.value() == 503,
                    (httpRequest, response) -> { }).body(String.class);
            JsonNode root = mapper.readTree(body);
            if (health) {
                String status = root.path("status").asText();
                return Set.of("UP", "DOWN", "OUT_OF_SERVICE").contains(status)
                        ? evidence(name, "AVAILABLE", status) : evidence(name, "INVALID", null);
            }
            for (JsonNode measurement : root.path("measurements")) {
                JsonNode value = measurement.path("value");
                if ("VALUE".equals(measurement.path("statistic").asText())
                        && value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0) {
                    return evidence(name, "AVAILABLE", value.asDouble());
                }
            }
            return evidence(name, "INVALID", null);
        } catch (Exception failure) {
            return evidence(name, "UNAVAILABLE", null);
        }
    }

    /**
     * 为证据记录实际完成时间。
     * @param name 证据名称
     * @param status 采集状态
     * @param value 已验证的值
     * @return 带时间戳的证据
     */
    private Observation evidence(String name, String status, Object value) {
        return new Observation(name, status, value, System.currentTimeMillis());
    }
}
