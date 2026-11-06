package com.example.opsaiagent.resilience;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 出站 HTTP 全局配置。
 *
 * 把超时统一施加到 Spring Boot 自动装配的 {@code RestClient.Builder}（注入该 Builder 的
 * webhook 通知器等自动获得连接/读取超时），避免无超时挂起。
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public RestClientCustomizer resilienceRestClientCustomizer(ResilienceProperties properties) {
        return builder -> builder.requestFactory(RestClientFactory.requestFactory(properties));
    }
}
