package com.example.opsaiagent.service;

import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

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

    /**
     * 统一的系统提示词：告诉模型它的角色、能力、回答要求
     */
    private static final String SYSTEM_PROMPT = """
        你是一个运维智能体。你的职责是帮助用户排查和诊断服务问题。

        你有两类信息源：
        1. 【参考资料】—— 来自运维知识库的文档片段
        2. 【工具】—— 可以调用工具查询服务的实时数据，包括：
           - queryServiceHealth：查健康状态
           - queryMetric：查具体指标的值
           - listMetrics：列出服务支持的所有指标

        回答原则：
        - 涉及运维知识 → 优先参考【参考资料】
        - 涉及实时数据 → 调用工具
        - 两者都涉及 → 先查资料再调工具，综合回答
        - 用户问"有哪些指标"或不确定指标名 → 用 listMetrics
        - 用户问具体指标的值 → 用 queryMetric

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
                .tools(healthCheckTools, metricQueryTools, metricDiscoveryTools)
                .call()
                .content();
        log.info("RAG 问答完成，召回 {} 个片段", documents.size());
        return answer;
    }
}
