package com.example.opsaiagent.registry;

import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.ops.service.OpsServiceManager;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 以数据库形式 服务注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbServiceRegistry implements ServiceRegistry {

    private final OpsServiceManager serviceManager;

    /**
     * 列出服务；存在租户上下文时只返回当前租户服务。
     *
     * @return 服务列表
     */
    @Override
    public List<ServiceInfo> listAll() {
        String tenantId = currentTenantId();
        List<OpsServiceEntity> services = tenantId == null
                ? serviceManager.listAll()
                : serviceManager.listByTenant(tenantId);
        return services.stream()
                .map(this::toServiceInfo)
                .collect(Collectors.toList());
    }

    /**
     * 根据名称获取服务；存在租户上下文时按租户隔离。
     *
     * @param name 服务名称
     * @return 服务信息
     */
    @Override
    public ServiceInfo getByName(String name) {
        String tenantId = currentTenantId();
        return (tenantId == null
                ? serviceManager.findByName(name)
                : serviceManager.findByName(tenantId, name))
                .map(this::toServiceInfo)
                .orElse(null);
    }

    /**
     * 当前租户；平台/无上下文返回 null，表示全局视角。
     *
     * @return 租户 ID
     */
    private String currentTenantId() {
        String tenantId = SecurityUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank() || "0".equals(tenantId)) {
            return null;
        }
        return tenantId;
    }

    /**
     * 将实体转换为服务信息
     * @param entity 实体
     * @return 服务信息
     */
    private ServiceInfo toServiceInfo(OpsServiceEntity entity) {
        ServiceInfo info = new ServiceInfo();
        info.setName(entity.getName());
        info.setBaseUrl(entity.getBaseUrl());
        info.setHealthPath(entity.getHealthPath());
        info.setMetricsPath(entity.getMetricsPath());
        info.setOwner(entity.getOwner());
        info.setEnv(entity.getEnv());
        return info;
    }
}
