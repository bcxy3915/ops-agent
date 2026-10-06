package com.example.opsaiagent.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 从被监控服务的 Actuator 端点拉取指标
 * 支持的指标：
 * - system.cpu.usage / process.cpu.usage
 * - jvm.memory.used / jvm.memory.max
 * - jvm.threads.live
 * - jvm.gc.pause
 * - http.server.requests
 * - hikaricp.connections.active / pending
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuatorMetricFetcher {

    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    /**
     * 从服务的 Actuator 拉取单个指标
     * @param baseUrl 服务的 Actuator 端点地址，例如 http://localhost:8080
     * @param metricName 指标名称
     * @return MetricValue；如果指标不存在或调用失败，返回 null
     */
    public MetricValue fetch(String baseUrl, String metricName) {
        try {
            String url = baseUrl + "/actuator/metrics/" + metricName;
            String body = restClient.get().uri(url).retrieve().body(String.class);

            JsonNode root = objectMapper.readTree(body);
            JsonNode measurements = root.path("measurements");

            if (!measurements.isArray() || measurements.isEmpty()) {
                return null;
            }

            // Actuator 返回 measurements 数组，可能包含 VALUE / COUNT / TOTAL_TIME / MAX
            double value = 0;
            double count = 0;
            double totalTime = 0;
            double max = 0;

            for (JsonNode m : measurements) {
                String stat = m.path("statistic").asText();
                double v = m.path("value").asDouble();
                switch (stat) {
                    case "VALUE" -> value = v;
                    case "COUNT" -> count = v;
                    case "TOTAL_TIME" -> totalTime = v;
                    case "MAX" -> max = v;
                }
            }

            return new MetricValue(value, count, totalTime, max);
        } catch (Exception e) {
            log.debug("拉取指标失败: {} - {} ({})", baseUrl, metricName, e.getMessage());
            return null;
        }
    }

    /**
     * 指标值封装
     * @param value 指标值
     * @param count 指标计数
     * @param totalTime 指标总时间
     * @param max 指标最大值
     * @return MetricValue
     */
    public record MetricValue(double value, double count, double totalTime, double max) {
        public static MetricValue empty() {
            return new MetricValue(0, 0, 0, 0);
        }
    }
}