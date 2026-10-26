package com.example.opsaiagent.registry;

import lombok.Data;

/**
 * 服务信息
 */
@Data
public class ServiceInfo {

    /**
     * 服务名
     */
    private String name;

    /**
     * 所属租户 ID
     */
    private String tenantId;

    /**
     * 基础地址，如 http://localhost:8081
     */
    private String baseUrl;

    /**
     * /actuator/health
     */
    private String healthPath;

    /**
     * /actuator/metrics
     */
    private String metricsPath;

    /**
     * 负责人
     */
    private String owner;

    /**
     * 环境
     */
    private String env;
}