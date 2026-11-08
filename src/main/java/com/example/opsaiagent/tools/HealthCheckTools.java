package com.example.opsaiagent.tools;

import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import com.example.opsaiagent.resilience.RestClientFactory;
import com.example.opsaiagent.tools.support.ToolErrorFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckTools {

    private final ServiceRegistry serviceRegistry;
    private final RestClient restClient = RestClientFactory.builder().build();

    /**
     * 查询注册服务的实时健康状态。
     * @param serviceName 服务名
     * @return 健康结果或明确的查询错误
     */
    public String queryServiceHealth(
            String serviceName) {

        ServiceInfo service = serviceRegistry.getByName(serviceName);
        if (service == null) {
            return "服务 " + serviceName + " 未注册，可用服务：" +
                   serviceRegistry.listAll().stream().map(ServiceInfo::getName).toList();
        }

        String url = service.getBaseUrl() + service.getHealthPath();
        try {
            String result = restClient.get().uri(url).retrieve().body(String.class);
            log.info("健康查询 {} -> {}", url, result);
            return "服务 " + serviceName + " 健康状态：" + result;
        } catch (Exception e) {
            log.error("健康查询失败", e);
            return ToolErrorFormatter.formatHttpError(serviceName, url, e);
        }
    }
}