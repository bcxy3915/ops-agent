package com.example.opsaiagent.service;

import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceHealthChecker {

    private final OpsServiceManager serviceManager;
    private final ServiceRegistry serviceRegistry;
    private final RestClient restClient = RestClient.create();

    /**
     * 立即检查一次指定服务的健康状态，返回最新状态
     * @param name 服务名称
     * @return 服务健康状态 "UP" 或 "DOWN"
     */
    public String checkNow(String name) {
        ServiceInfo info = serviceRegistry.getByName(name);
        if (info == null) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "服务不存在: " + name);
        }

        String status;
        try {
            String url = info.getBaseUrl() + info.getHealthPath();
            String body = restClient.get().uri(url).retrieve().body(String.class);
            status = (body != null && body.contains("\"status\":\"UP\"")) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("健康检查失败: {} - {}", name, e.getMessage());
            status = "DOWN";
        }

        // 更新数据库
        String finalStatus = status;
        serviceManager.findByName(name).ifPresent(entity ->
                serviceManager.updateStatus(entity.getId(), finalStatus));

        return status;
    }
}