package com.example.opsaiagent.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天记忆配置类
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 创建一个聊天记忆
     * @return 聊天记忆
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20) // 每个会话保留最近 20 条消息
                .build();
    }
}
