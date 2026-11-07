package com.example.opsaiagent.ai.adapter.springai;

import com.example.opsaiagent.ai.api.ChatGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring AI 同步聊天网关配置。 */
@Configuration
@ConditionalOnProperty(prefix = "ops-agent.ai", name = "provider", havingValue = "spring-ai", matchIfMissing = true)
public class SpringAiChatGatewayConfig {

    /**
     * 注册 Spring AI 同步聊天网关。
     *
     * @param chatClient Spring AI 聊天客户端
     * @return 同步聊天网关
     */
    @Bean
    public ChatGateway springAiChatGateway(ChatClient chatClient) {
        return new SpringAiChatGateway(chatClient);
    }
}
