package com.example.opsaiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring MVC 异步请求线程池配置
 * 场景：SSE 流式接口返回 Flux<String>，Spring MVC 需要异步线程池处理
 * 默认的 SimpleAsyncTaskExecutor 每次请求新建线程，不适合生产。
 * 这里配置一个带队列和拒绝策略的线程池。
 */
@Slf4j
@Configuration
public class AsyncConfig implements WebMvcConfigurer {

    /**
     * MVC 异步请求专用线程池
     */
    @Bean("mvcAsyncExecutor")
    public Executor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);              // 核心线程数
        executor.setMaxPoolSize(50);               // 最大线程数
        executor.setQueueCapacity(200);            // 队列容量
        executor.setKeepAliveSeconds(60);          // 空闲线程存活时间
        executor.setThreadNamePrefix("mvc-async-");// 线程名前缀
        executor.setWaitForTasksToCompleteOnShutdown(true);   // 停机时等任务完成
        executor.setAwaitTerminationSeconds(30);   // 最多等 30 秒
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.CallerRunsPolicy()   // 队列满时由调用线程执行
        );
        executor.initialize();
        return executor;
    }

    /**
     * 把线程池挂到 MVC 异步支持上
     * @param configurer 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor((AsyncTaskExecutor) mvcAsyncExecutor());
        configurer.setDefaultTimeout(300_000);   // 异步超时 5 分钟（毫秒）
        log.info("MVC 异步线程池配置完成：core=10, max=50, queue=200");
    }
}