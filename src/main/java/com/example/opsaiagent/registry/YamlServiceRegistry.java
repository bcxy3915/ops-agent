package com.example.opsaiagent.registry;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML 服务注册表
 */
@Slf4j
@Component
public class YamlServiceRegistry implements ServiceRegistry {

    private final Map<String, ServiceInfo> services = new HashMap<>();

    /**
     * 初始化服务注册表
     */
    @PostConstruct
    public void init() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("services.yml")) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(in);
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("services");
            for (Map<String, Object> item : list) {
                ServiceInfo info = new ServiceInfo();
                info.setName((String) item.get("name"));
                info.setBaseUrl((String) item.get("base-url"));
                info.setHealthPath((String) item.get("health-path"));
                info.setMetricsPath((String) item.get("metrics-path"));
                info.setOwner((String) item.get("owner"));
                info.setEnv((String) item.get("env"));
                services.put(info.getName(), info);
                log.info("注册服务: {} -> {}", info.getName(), info.getBaseUrl());
            }
        } catch (Exception e) {
            log.error("加载 services.yml 失败", e);
        }
    }

    /**
     * 列出所有服务
     */
    @Override
    public List<ServiceInfo> listAll() {
        return new ArrayList<>(services.values());
    }

    /**
     * 根据名称获取服务
     */
    @Override
    public ServiceInfo getByName(String name) {
        return services.get(name);
    }
}