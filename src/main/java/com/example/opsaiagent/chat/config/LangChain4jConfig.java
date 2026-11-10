package com.example.opsaiagent.chat.config;

import java.time.Duration;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStructuredOutputGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStreamingChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.ConversationChatMemoryStore;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsAssistant;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsStreamingAssistant;
import com.example.opsaiagent.ai.adapter.langchain4j.ContextAwareStreamingChatModel;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jKnowledgeContentRetriever;
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
import com.example.opsaiagent.retrieval.HybridRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** LangChain4j 生产模型配置。 */
@Configuration
public class LangChain4jConfig {

    @Value("${ops-agent.ai.api-key:${spring.ai.openai.api-key:${AI_API_KEY:}}}")
    private String apiKey;

    @Value("${ops-agent.ai.model:${spring.ai.openai.chat.options.model:${spring.ai.openai.chat.model:deepseek-chat}}}")
    private String modelName;

    @Value("${ops-agent.ai.timeout:${spring.ai.openai.chat.timeout:60s}}")
    private String timeout;

    @Value("${ops-agent.ai.temperature:${spring.ai.openai.chat.temperature:0.7}}")
    private double temperature;

    @Value("${ops-agent.chat.memory.max-entries:1000}")
    private int capacity;

    @Value("${ops-agent.chat.memory.ttl:600s}")
    private Duration ttl;

    @Value("${ops-agent.chat.turn.timeout:300s}")
    private Duration turnTimeout;

    @Value("${ops-agent.ai.base-url:${spring.ai.openai.base-url:https://api.deepseek.com/v1}}")
    private String baseUrl;

    /**
     * 创建 LangChain4j OpenAI 协议模型客户端。
     *
     * 无入参。
     * @return LangChain4j 聊天模型
     */
    @Bean
    public ChatModel langChain4jChatModel() {
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
     * 无入参。
     * @return LangChain4j 流式聊天模型
     */
    @Bean
    public OpenAiStreamingChatModel langChain4jStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(DurationStyle.detectAndParse(timeout))
                .temperature(temperature)
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
    public ConversationChatMemoryStore langChain4jChatMemoryStore(ConversationService conversationService) {
        if (ttl.compareTo(turnTimeout) <= 0) {
            throw new IllegalArgumentException("记忆过期时间必须大于整轮超时");
        }
        return new ConversationChatMemoryStore(conversationService, 20, capacity, ttl, System::nanoTime);
    }

    /**
     * 注册带有会话记忆和只读工具的 LangChain4j 运维助手。
     *
     * @param chatModel LangChain4j 聊天模型
     * @param chatMemoryStore 用户隔离的会话记忆仓库
     * @param tools 四个现有只读运维工具
     * @param contentRetriever LangChain4j 知识内容检索器
     * @return LangChain4j 运维助手
     */
    @Bean
    public OpsAssistant langChain4jOpsAssistant(ChatModel chatModel,
                                                ChatMemoryStore chatMemoryStore,
                                                LangChain4jReadOnlyToolBindings tools,
                                                LangChain4jKnowledgeContentRetriever contentRetriever) {
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(chatMemoryStore)
                .build();
        return AiServices.builder(OpsAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .tools(tools)
                .contentRetriever(contentRetriever)
                .storeRetrievedContentInChatMemory(false)
                .beforeToolExecution(tool -> chatMemoryStore.getMessages(tool.invocationContext().chatMemoryId()))
                .chatRequestTransformer((request, memoryId) -> {
                    chatMemoryStore.getMessages(memoryId);
                    return request;
                })
                .maxToolCallingRoundTrips(8)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
    }

    /**
     * 注册带有会话记忆、知识检索和只读工具的 LangChain4j 流式运维助手。
     *
     * @param streamingChatModel LangChain4j 流式聊天模型
     * @param chatMemoryStore 用户隔离的会话记忆仓库
     * @param tools 四个现有只读运维工具
     * @param contentRetriever LangChain4j 知识内容检索器
     * @return LangChain4j 流式运维助手
     */
    @Bean
    public OpsStreamingAssistant langChain4jOpsStreamingAssistant(
            OpenAiStreamingChatModel streamingChatModel,
            ChatMemoryStore chatMemoryStore,
            LangChain4jReadOnlyToolBindings tools,
            LangChain4jKnowledgeContentRetriever contentRetriever) {
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(chatMemoryStore)
                .build();
        return AiServices.builder(OpsStreamingAssistant.class)
                .streamingChatModel(new ContextAwareStreamingChatModel(streamingChatModel))
                .chatMemoryProvider(memoryProvider)
                .tools(tools)
                .contentRetriever(contentRetriever)
                .storeRetrievedContentInChatMemory(false)
                .beforeToolExecution(tool -> chatMemoryStore.getMessages(tool.invocationContext().chatMemoryId()))
                .chatRequestTransformer((request, memoryId) -> {
                    chatMemoryStore.getMessages(memoryId);
                    return request;
                })
                .maxToolCallingRoundTrips(8)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
    }

    /**
     * 注册基于现有混合检索的 LangChain4j RAG 内容检索器。
     *
     * @param hybridRetriever 现有向量、BM25、融合和重排检索服务
     * @param memoryStore 轮次检索快照仓库
     * @return LangChain4j RAG 内容检索器
     */
    @Bean
    public LangChain4jKnowledgeContentRetriever langChain4jKnowledgeContentRetriever(
            HybridRetriever hybridRetriever, ConversationChatMemoryStore memoryStore) {
        return new LangChain4jKnowledgeContentRetriever(hybridRetriever, 5, memoryStore);
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
    public LangChain4jReadOnlyToolBindings langChain4jReadOnlyToolBindings(
            HealthCheckTools healthCheckTools,
            MetricQueryTools metricQueryTools,
            MetricDiscoveryTools metricDiscoveryTools,
            ServiceDiscoveryTools serviceDiscoveryTools) {
        return new LangChain4jReadOnlyToolBindings(healthCheckTools, metricQueryTools, metricDiscoveryTools, serviceDiscoveryTools);
    }
}
