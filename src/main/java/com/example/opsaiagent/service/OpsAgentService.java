package com.example.opsaiagent.service;

import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import com.example.opsaiagent.tools.ServiceDiscoveryTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * RAG服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAgentService {

    // 聊天客户端
    private final ChatClient chatClient;
    // 向量存储
    private final VectorStore vectorStore;
    private final HealthCheckTools healthCheckTools;
    private final MetricQueryTools metricQueryTools;
    private final MetricDiscoveryTools metricDiscoveryTools;
    private final ServiceDiscoveryTools serviceDiscoveryTools;

    /**
     * 统一的系统提示词：告诉模型它的角色、能力、回答要求
     */
    private static final String SYSTEM_PROMPT = """ 
        你是一个运维智能体。你的职责是帮助用户排查和诊断服务问题。

        【强制规则】
        1. 用户问题中出现服务名（如 todo-service）时：
           - 问"有哪些指标" → 必须调用 listMetrics
           - 问"XX 指标的值" → 必须调用 queryMetric
           - 问"健康吗" → 必须调用 queryServiceHealth
           - 问"哪些服务异常" → 必须调用 listServicesByStatus
        2. 参考资料只提供知识背景，不能代替实时数据。涉及具体服务的问题必须调工具。
        3. 只有当问题不含任何服务名时，才用参考资料回答。

        【输出规范】
        - 直接输出最终答案，不要输出过程描述
        - 全部使用中文，不要输出任何英文句子
        - 不要以 "I'll..."、"Let me..."、"Checking..." 开头
        - 第一句话就是答案本身

        【措辞规范】
        - 严格区分以下三种情况：
          · 服务未注册 → "服务未注册"
          · 服务已注册但连不上 → "无法获取服务状态"
          · 服务返回 DOWN → "服务不健康"
        - 不要混淆"不健康"和"无法获取状态"

        【参考资料】
        {context}
        """;


    /**
     * 根据问题生成答案
     * @param sessionId 会话ID
     * @param question 问题
     * @return 答案
     */
    public String ask(String sessionId, String question) {
        // 1.向量检索，从PgVector中召回相关片段
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(8)                    // 召回8个相关片段
                        .similarityThreshold(0.5)   // 相似度阈值
                        .build()
        );
        String context;
        if (documents.isEmpty()) {
            context = "（知识库中未找到相关内容，请基于工具查询或已有知识回答）";
        } else {
            context = documents.stream()
                    .map(d -> "【来源：" + d.getMetadata().getOrDefault("source", "未知文档") + "】\n"
                            + Objects.requireNonNullElse(d.getText(), ""))
                    .collect(Collectors.joining("\n\n---\n\n"));
        }
        log.info("问题: {}", question);
        // 打印召回的片段
        documents.forEach(d -> log.info("召回片段[来源={}][距离={}]: {}",
                d.getMetadata().get("source"),
                d.getMetadata().get("distance"),
                Objects.requireNonNull(d.getText()).substring(0, Math.min(150, d.getText().length()))));

        // 3.Prompt 模板填充
        PromptTemplate template = new PromptTemplate(SYSTEM_PROMPT);
        String systemPrompt = template.render(Map.of("context", context));

        // 4.调用聊天模型生成答案
        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)) // 指定会话ID，用于聊天记忆
                .tools(healthCheckTools, metricQueryTools, metricDiscoveryTools, serviceDiscoveryTools)
                .call()
                .content();
        log.info("RAG 问答完成，召回 {} 个片段", documents.size());
        return answer;
    }

    /**
     * 根据问题生成答案 流式问答：逐字返回答案
     * @param sessionId 会话ID
     * @param question 问题
     * @return 答案
     */
    public Flux<String> askStream(String sessionId, String question) {
        // 1.RAG检索
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(8)
                .similarityThreshold(0.5)
                .build());

        String context;
        if (documents.isEmpty()) {
            context = "（知识库中未找到相关内容，请基于工具查询或已有知识回答）";
        } else {
            context = documents.stream()
                    .map(d -> "【来源：" + d.getMetadata().getOrDefault("source", "未知文档") + "】\n"
                            + Objects.requireNonNullElse(d.getText(), ""))
                    .collect(Collectors.joining("\n\n---\n\n"));
        }

        // 2.拼接 System Prompt
        String systemPrompt = new PromptTemplate(SYSTEM_PROMPT).render(Map.of("context", context));

        // 3.流式调用（关键：.stream() 替代 .call()）
        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)) // 指定会话ID，用于聊天记忆
                .tools(healthCheckTools, metricQueryTools, metricDiscoveryTools, serviceDiscoveryTools)
                .stream()
                .content();
    }
}
