package com.example.opsaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** 应用入口；聊天回退尚在，显式排除其已替换的向量模型自动配置。 */
@SpringBootApplication(exclude = {
        UserDetailsServiceAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class
})
public class OpsAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsAiAgentApplication.class, args);
    }

}
