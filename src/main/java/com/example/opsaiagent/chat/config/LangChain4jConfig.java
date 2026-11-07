package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStructuredOutputGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStreamingChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.ConversationChatMemoryStore;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsAssistant;
import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import com.example.opsaiagent.ai.api.StreamingChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import com.example.opsaiagent.tools.ServiceDiscoveryTools;
import com.example.opsaiagent.tools.langchain4j.LangChain4jReadOnlyToolBindings;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

    /**
     * 创建 LangChain4j OpenAI 协议流式模型客户端。
     *
     * @param baseUrl 模型服务地址
     * @param apiKey 模型服务密钥
     * @param modelName 模型名称
     * @param timeout 超时时间
     * @return LangChain4j 流式聊天模型
     */
    @Bean
    public OpenAiStreamingChatModel langChain4jStreamingChatModel(
            @Value("${ops-agent.ai.base-url:${spring.ai.openai.base-url:https://api.openai.com/v1}}") String baseUrl,
            @Value("${ops-agent.ai.api-key:${spring.ai.openai.api-key:}}") String apiKey,
            @Value("${ops-agent.ai.model:${spring.ai.openai.chat.options.model:gpt-4o-mini}}") String modelName,
            @Value("${ops-agent.ai.timeout:60s}") String timeout) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(DurationStyle.detectAndParse(timeout))
                .build();
    }

    /**
     * 注册 LangChain4j 流式聊天网关。
     *
     * @param chatModel LangChain4j 流式聊天模型
     * @return 流式聊天网关
     */
    @Bean
    public StreamingChatGateway langChain4jStreamingChatGateway(OpenAiStreamingChatModel chatModel) {
        return new LangChain4jStreamingChatGateway(chatModel);
    }

    /**
     * 注册基于现有会话历史的 LangChain4j 记忆仓库。
     *
     * @param conversationService 现有会话历史服务
     * @return 会话记忆仓库
     */
    @Bean
    @ConditionalOnBean(ConversationService.class)
    public ChatMemoryStore langChain4jChatMemoryStore(ConversationService conversationService) {
        return new ConversationChatMemoryStore(conversationService, 20);
    }

    /**
     * 注册最近 20 条消息窗口的 LangChain4j 聊天记忆。
     *
     * @param chatMemoryStore 用户隔离的会话记忆仓库
     * @return LangChain4j 聊天记忆
     */
    @Bean
    @ConditionalOnBean(ConversationService.class)
    public ChatMemory langChain4jChatMemory(ChatMemoryStore chatMemoryStore) {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * 注册带有会话记忆和只读工具的 LangChain4j 运维助手。
     *
     * @param chatModel LangChain4j 聊天模型
     * @param chatMemoryStore 用户隔离的会话记忆仓库
     * @param tools 四个现有只读运维工具
     * @return LangChain4j 运维助手
     */
    @Bean
    @ConditionalOnBean({ConversationService.class, HealthCheckTools.class, MetricQueryTools.class,
            MetricDiscoveryTools.class, ServiceDiscoveryTools.class})
    public OpsAssistant langChain4jOpsAssistant(ChatModel chatModel,
                                                ChatMemoryStore chatMemoryStore,
                                                LangChain4jReadOnlyToolBindings tools) {
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(chatMemoryStore)
                .build();
        return AiServices.builder(OpsAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .tools(tools)
                .maxToolCallingRoundTrips(8)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
    }

    /**
     * 注册 LangChain4j 只读运维工具。
     *
     * @param healthCheckTools 健康检查能力
     * @param metricQueryTools 指标查询能力
     * @param metricDiscoveryTools 指标发现能力
     * @param serviceDiscoveryTools 服务发现能力
     * @return 只读工具门面
     */
    @Bean
    @ConditionalOnBean({HealthCheckTools.class, MetricQueryTools.class,
            MetricDiscoveryTools.class, ServiceDiscoveryTools.class})
    public LangChain4jReadOnlyToolBindings langChain4jReadOnlyToolBindings(
            HealthCheckTools healthCheckTools,
            MetricQueryTools metricQueryTools,
            MetricDiscoveryTools metricDiscoveryTools,
            ServiceDiscoveryTools serviceDiscoveryTools) {
        return new LangChain4jReadOnlyToolBindings(
                healthCheckTools, metricQueryTools, metricDiscoveryTools, serviceDiscoveryTools);
    }
}
