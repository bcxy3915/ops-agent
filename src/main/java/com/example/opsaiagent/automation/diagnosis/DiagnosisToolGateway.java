package com.example.opsaiagent.automation.diagnosis;

import com.example.opsaiagent.inspection.config.BusinessProbeProperties;
import com.example.opsaiagent.inspection.config.InspectionLogProperties;
import com.example.opsaiagent.inspection.service.BusinessProbeService;
import com.example.opsaiagent.inspection.service.InspectionLogService;
import com.example.opsaiagent.inspection.service.InspectionMetricWindowService;
import com.example.opsaiagent.inspection.service.InspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 模型只能选择已登记参数，不能改变目标服务、时间窗或读取预算。 */
@Service
@RequiredArgsConstructor
public class DiagnosisToolGateway {
    private static final List<String> METRICS = List.of("process.cpu.usage", "hikaricp.connections.active",
            "hikaricp.connections.max", "hikaricp.connections.pending");
    private final InspectionService inspection;
    private final InspectionMetricWindowService windows;
    private final InspectionLogService logs;
    private final BusinessProbeService probes;
    private final InspectionLogProperties logProperties;
    private final BusinessProbeProperties probeProperties;

    /**
     * 获取当前服务专属工具目录，仅包含标识，不暴露路径和凭据。
     * @param serviceId 已授权服务 ID
     * @return 可用工具及参数目录
     */
    public Map<String, List<String>> catalog(String serviceId) {
        inspection.requireVisibleService(serviceId);
        return Map.of("METRIC_WINDOW", METRICS,
                "LOGS", logProperties.getSources().entrySet().stream()
                        .filter(item -> serviceId.equals(item.getValue().getServiceId())).map(Map.Entry::getKey).sorted().toList(),
                "BUSINESS_PROBE", probeProperties.getProbes().entrySet().stream()
                        .filter(item -> serviceId.equals(item.getValue().getServiceId())).map(Map.Entry::getKey).sorted().toList());
    }

    /**
     * 执行单一允许的只读取证动作，执行前再次检查实时授权和绑定。
     * @param serviceId 固定目标服务 ID
     * @param action 白名单动作
     * @param argument 白名单参数
     * @param endMs 任务创建时固定的历史窗口终点
     * @return 受控工具的实际结果
     */
    public Object execute(String serviceId, String action, String argument, long endMs) {
        if (!catalog(serviceId).getOrDefault(action, List.of()).contains(argument)) {
            throw new IllegalArgumentException("诊断工具或参数不在当前服务目录中");
        }
        return switch (action) {
            case "METRIC_WINDOW" -> windows.compare(serviceId, argument, 5, endMs);
            case "LOGS" -> logs.read(serviceId, argument, endMs - 300_000, endMs, 100);
            case "BUSINESS_PROBE" -> probes.check(serviceId, argument);
            default -> throw new IllegalArgumentException("不支持的诊断动作");
        };
    }
}
