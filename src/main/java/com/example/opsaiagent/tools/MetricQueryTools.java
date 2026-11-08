package com.example.opsaiagent.tools;

import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import com.example.opsaiagent.resilience.RestClientFactory;
import com.example.opsaiagent.tools.support.ToolErrorFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueryTools {

    private final ServiceRegistry serviceRegistry;
    private final RestClient restClient = RestClientFactory.builder().build();

    /**
     * 查询注册服务的指定监控指标。
     * @param serviceName 服务名
     * @param metricName 指标名
     * @return 指标内容或明确的查询错误
     */
    public String queryMetric(
            String serviceName,
            String metricName) {

        ServiceInfo service = serviceRegistry.getByName(serviceName);
        if (service == null) {
            return "服务 " + serviceName + " 未注册";
        }

        String url = service.getBaseUrl() + service.getMetricsPath() + "/" + metricName;
        try {
            Map<String, Object> result = restClient.get().uri(url).retrieve().body(Map.class);
            log.info("指标查询 {} -> {}", url, result);
            return formatMetric(serviceName, metricName, result);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("指标不存在: {} - {}", serviceName, metricName);
            return "指标 " + metricName + " 在当前服务上不存在。可能原因：" +
                    "1) 该指标未被 Actuator 暴露；" +
                    "2) 该指标需要特定条件才会出现（如 GC 指标需要发生过 GC）。" +
                    "建议先访问 /actuator/metrics 查看该服务支持的所有指标。";
        } catch (Exception e) {
            log.error("指标查询失败", e);
            return ToolErrorFormatter.formatHttpError(serviceName, url, e);
        }
    }

    /**
     * 格式化指标结果
     * @param serviceName 服务名
     * @param metricName 指标名
     * @param result 指标结果
     * @return 格式化后的指标结果
     */
    private String formatMetric(String serviceName, String metricName, Map<String, Object> result) {
        if (result == null || !result.containsKey("measurements")) {
            return "指标 " + metricName + " 无数据";
        }
        Object measurements = result.get("measurements");
        return "服务 " + serviceName + " 指标 " + metricName + "：" + measurements;
    }
}