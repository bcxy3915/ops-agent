package com.example.opsaiagent.tools.langchain4j;

import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import com.example.opsaiagent.tools.ServiceDiscoveryTools;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** 复用现有业务能力的 LangChain4j 只读工具门面。 */
public class LangChain4jReadOnlyToolBindings {

    private final HealthCheckTools healthCheckTools;
    private final MetricQueryTools metricQueryTools;
    private final MetricDiscoveryTools metricDiscoveryTools;
    private final ServiceDiscoveryTools serviceDiscoveryTools;

    /**
     * 创建 LangChain4j 只读工具门面。
     *
     * @param healthCheckTools 健康检查能力
     * @param metricQueryTools 指标查询能力
     * @param metricDiscoveryTools 指标发现能力
     * @param serviceDiscoveryTools 服务发现能力
     */
    public LangChain4jReadOnlyToolBindings(HealthCheckTools healthCheckTools,
                                           MetricQueryTools metricQueryTools,
                                           MetricDiscoveryTools metricDiscoveryTools,
                                           ServiceDiscoveryTools serviceDiscoveryTools) {
        this.healthCheckTools = healthCheckTools;
        this.metricQueryTools = metricQueryTools;
        this.metricDiscoveryTools = metricDiscoveryTools;
        this.serviceDiscoveryTools = serviceDiscoveryTools;
    }

    /**
     * 查询指定服务健康状态。
     *
     * @param serviceName 服务名称
     * @return 健康检查结果
     */
    @Tool("查询指定服务的健康状态，返回 UP 表示健康，DOWN 表示异常。")
    public String queryServiceHealth(@P("服务名，例如 todo-service") String serviceName) {
        return healthCheckTools.queryServiceHealth(serviceName);
    }

    /**
     * 查询指定服务的监控指标。
     *
     * @param serviceName 服务名称
     * @param metricName 指标名称
     * @return 指标查询结果
     */
    @Tool("查询指定服务的指定监控指标。")
    public String queryMetric(@P("服务名，例如 todo-service") String serviceName,
                              @P("指标名，例如 system.cpu.usage") String metricName) {
        return metricQueryTools.queryMetric(serviceName, metricName);
    }

    /**
     * 列出指定服务支持的监控指标。
     *
     * @param serviceName 服务名称
     * @return 指标发现结果
     */
    @Tool("列出指定服务支持的所有监控指标名。")
    public String listMetrics(@P("服务名，例如 todo-service") String serviceName) {
        return metricDiscoveryTools.listMetrics(serviceName);
    }

    /**
     * 按健康状态查询服务列表。
     *
     * @param status 服务状态
     * @return 服务发现结果
     */
    @Tool("查询指定状态的所有服务。状态可选：UP、DOWN、UNKNOWN。")
    public String listServicesByStatus(@P("服务状态：UP、DOWN 或 UNKNOWN") String status) {
        return serviceDiscoveryTools.listServicesByStatus(status);
    }
}
