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

import java.util.List;
import java.util.Map;

/**
 * 指标发现工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricDiscoveryTools {
    private final ServiceRegistry serviceRegistry;
    private final RestClient restClient = RestClient.create();

    @Tool(description = "列出指定服务支持的所有监控指标名。当用户问某服务有哪些监控指标，或不确定具体指标名时使用")
    public String listMetrics(@ToolParam(description = "服务名, 例如todo-service") String serviceName) {
        ServiceInfo service = serviceRegistry.getByName(serviceName);
        if (service == null) {
            return "服务 " + serviceName + " 未注册。可用服务：" +
                    serviceRegistry.listAll().stream().map(ServiceInfo::getName).toList();
        }

        String url = service.getBaseUrl() + service.getMetricsPath();
        try {
            Map<String, Object> result = restClient.get().uri(url).retrieve().body(Map.class);

            if (result == null || !result.containsKey("names")) {
                return "服务 " + serviceName + " 未返回指标列表";
            }

            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) result.get("names");

            // 过滤出关键指标，避免列表过长
            List<String> filtered = names.stream()
                    .filter(n -> n.startsWith("system.")
                            || n.startsWith("jvm.")
                            || n.startsWith("http.")
                            || n.startsWith("hikaricp.")
                            || n.startsWith("process."))
                    .limit(30)
                    .toList();

            log.info("指标列表查询 {} -> 共 {} 个，过滤后 {} 个", url, names.size(), filtered.size());

            return "服务 " + serviceName + " 共支持 " + names.size() + " 个指标，"
                    + "其中关键指标（共 " + filtered.size() + " 个）：\n"
                    + String.join("\n", filtered);

        } catch (Exception e) {
            log.error("指标列表查询失败", e);
            return ToolErrorFormatter.formatHttpError(serviceName, url, e);
        }
    }
}
