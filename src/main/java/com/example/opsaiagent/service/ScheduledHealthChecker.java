package com.example.opsaiagent.service;

import com.example.opsaiagent.entity.OpsServiceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledHealthChecker {

    private final OpsServiceManager serviceManager;
    private final ServiceHealthChecker healthChecker;

    /**
     * 每 30 秒检查一次所有服务的健康状态
     * fixedDelay：上一次执行完成后，等 30 秒再执行下一次
     * initialDelay：启动后等 10 秒再开始第一次检查
     */
//    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000) TODO 暂不开启
    public void checkAllServices() {
        List<OpsServiceEntity> services = serviceManager.listAll();
        if (services.isEmpty()) {
            return;
        }

        log.debug("开始批量健康检查，共 {} 个服务", services.size());
        int up = 0, down = 0;

        for (OpsServiceEntity service : services) {
            try {
                String status = healthChecker.checkNow(service.getName());
                if ("UP".equals(status)) up++;
                else down++;
            } catch (Exception e) {
                log.warn("服务 {} 健康检查异常: {}", service.getName(), e.getMessage());
                down++;
            }
        }
        log.info("健康检查完成: UP={}, DOWN={}", up, down);
    }
}
