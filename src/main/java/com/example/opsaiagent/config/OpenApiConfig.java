package com.example.opsaiagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI opsAgentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ops Agent API")
                        .description("""
                                通用运维智能体 - REST API 文档

                                提供：
                                - 智能问答（同步 + 流式）
                                - 服务注册与管理
                                - 健康检查触发""")
                        .version("v1.0")
                        .contact(new Contact()
                                .name("Ops Agent")
                                .url("https://github.com/your-username/ops-agent"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
