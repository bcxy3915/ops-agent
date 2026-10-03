package com.example.opsaiagent.tools;

import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import com.example.opsaiagent.tools.support.ToolErrorFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckTools {

    private final ServiceRegistry serviceRegistry;
    private final RestClient restClient = RestClient.create();

    @Tool(description = "查询指定服务的健康状态，返回 UP 表示健康，DOWN 表示异常。服务名如 todo-service")
    public String queryServiceHealth(
            @ToolParam(description = "服务名，例如 todo-service") String serviceName) {

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