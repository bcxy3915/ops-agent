package com.example.opsaiagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);                    // 3 个线程
        scheduler.setThreadNamePrefix("ops-sched-"); // 线程名前缀，方便排查
        scheduler.setWaitForTasksToCompleteOnShutdown(true);  // 停机时等任务完成
        scheduler.initialize();
        // 注册任务调度器
        registrar.setTaskScheduler(scheduler);
    }
}

