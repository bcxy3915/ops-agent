package com.example.opsaiagent.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 配置。
 *
 * Spring Boot 4 默认使用 Jackson 3，而项目现有 DTO、敏感字段序列化器和第三方
 * Spring AI 组件仍基于 Jackson 2。这里显式提供 Jackson 2 ObjectMapper，
 * 并通过 {@code spring.http.converters.preferred-json-mapper=jackson2}
 * 让 Spring MVC 继续使用 Jackson 2，避免脱敏注解失效。
 */
@Configuration
public class JacksonConfig {

    /**
     * Jackson 2 ObjectMapper；保留 JavaTime 等模块支持。
     *
     * @return ObjectMapper
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // 与 Spring Boot 默认行为保持一致：忽略请求体中 DTO 未声明的字段，
                // 否则前端回传完整对象（如带 name）时会直接 500
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
