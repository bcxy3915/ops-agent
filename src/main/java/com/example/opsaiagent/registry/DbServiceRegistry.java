package com.example.opsaiagent.registry;

import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.service.OpsServiceManager;
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
     * 列出所有服务
     * @return 服务列表
     */
    @Override
    public List<ServiceInfo> listAll() {
        return serviceManager.listAll().stream()
                .map(this::toServiceInfo)
                .collect(Collectors.toList());
    }

    /**
     * 根据名称获取服务
     * @param name 服务名称
     * @return 服务信息
     */
    @Override
    public ServiceInfo getByName(String name) {
        return serviceManager.findByName(name)
                .map(this::toServiceInfo)
                .orElse(null);
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
