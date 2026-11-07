package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.springai.SpringAiStructuredOutputGateway;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI配置类
 */
@Configuration
public class AiConfig {

    /**
     * 配置ChatClient Bean
     * @param chatModel 聊天模型
     * @param chatMemory 聊天记忆
     * @return ChatClient
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    /**
     * 配置 Spring AI 结构化输出网关。
     *
     * @param chatClient 聊天客户端
     * @return 结构化输出网关
     */
    @Bean
    public StructuredOutputGateway structuredOutputGateway(ChatClient chatClient) {
        return new SpringAiStructuredOutputGateway(chatClient);
    }
}
