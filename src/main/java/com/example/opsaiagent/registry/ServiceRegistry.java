package com.example.opsaiagent.registry;

import java.util.List;

/**
 * 服务注册表
 */
public interface ServiceRegistry {

    /**
     * 列出所有服务
     */
    List<ServiceInfo> listAll();

    /**
     * 根据名称获取服务
     */
    ServiceInfo getByName(String name);
}