package com.example.opsaiagent.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AuditAsyncConfig {

    // 配置异步任务
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);            // 核心线程数
        executor.setMaxPoolSize(4);             // 最大线程数
        executor.setQueueCapacity(500);         // 队列容量
        executor.setThreadNamePrefix("audit-"); // 线程名称前缀
        executor.setWaitForTasksToCompleteOnShutdown(true); // 等待任务完成后再关闭
        executor.setAwaitTerminationSeconds(10);            // 等待时间

        executor.initialize();
        return executor;
    }
}
