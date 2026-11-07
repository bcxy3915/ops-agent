package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStructuredOutputGateway;
import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** LangChain4j 生产模型配置。 */
@Configuration
@ConditionalOnProperty(prefix = "ops-agent.ai", name = "provider", havingValue = "langchain4j")
public class LangChain4jConfig {

    /**
     * 创建 LangChain4j OpenAI 协议模型客户端。
     *
     * @param baseUrl 模型服务地址
     * @param apiKey 模型服务密钥
     * @param modelName 模型名称
     * @param timeout 超时时间
     * @param temperature 采样温度
     * @return LangChain4j 聊天模型
     */
    @Bean
    public ChatModel langChain4jChatModel(
            @Value("${ops-agent.ai.base-url:${spring.ai.openai.base-url:https://api.openai.com/v1}}") String baseUrl,
            @Value("${ops-agent.ai.api-key:${spring.ai.openai.api-key:}}") String apiKey,
            @Value("${ops-agent.ai.model:${spring.ai.openai.chat.options.model:gpt-4o-mini}}") String modelName,
            @Value("${ops-agent.ai.timeout:60s}") String timeout,
            @Value("${ops-agent.ai.temperature:0.7}") double temperature) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(DurationStyle.detectAndParse(timeout))
                .temperature(temperature)
                .build();
    }

    /**
     * 注册 LangChain4j 同步聊天网关。
     *
     * @param chatModel LangChain4j 聊天模型
     * @return 同步聊天网关
     */
    @Bean
    public ChatGateway langChain4jChatGateway(ChatModel chatModel) {
        return new LangChain4jChatGateway(chatModel);
    }

    /**
     * 注册 LangChain4j 结构化输出网关。
     *
     * @param chatModel LangChain4j 聊天模型
     * @param objectMapper JSON 映射器
     * @return 结构化输出网关
     */
    @Bean
    public StructuredOutputGateway langChain4jStructuredOutputGateway(ChatModel chatModel, ObjectMapper objectMapper) {
        return new LangChain4jStructuredOutputGateway(chatModel, objectMapper);
    }
}
