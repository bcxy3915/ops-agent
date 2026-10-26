package com.example.opsaiagent.chat.service;

import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.rbac.context.RbacContextSnapshot;
import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import com.example.opsaiagent.tools.ServiceDiscoveryTools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private final HealthCheckTools healthCheckTools;
    private final MetricQueryTools metricQueryTools;
    private final MetricDiscoveryTools metricDiscoveryTools;
    private final ServiceDiscoveryTools serviceDiscoveryTools;
    private final HybridRetriever hybridRetriever;
    private final ConversationService conversationService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeHitLogService knowledgeHitLogService;
    private final ObjectMapper objectMapper;

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
     * 根据问题生成答案（兼容入口：仅返回答案文本）
     * @param sessionId 会话ID
     * @param question 问题
     * @return 答案
     */
    public String ask(String sessionId, String question) {
        return askWithCitations(sessionId, question).getAnswer();
    }

    /**
     * 根据问题生成答案，并携带结构化引用 citations
     * @param sessionId 会话ID
     * @param question 问题
     * @return 答案 + 引用列表
     */
    public AskResponse askWithCitations(String sessionId, String question) {
        log.info("问题: {}", question);

        // 1.混合检索
        List<Document> documents = hybridRetriever.retrieve(question, 5);
        String context = buildContext(documents);

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

        // 5.构建结构化引用
        List<RetrievalHitResponse> citations = knowledgeRetrievalService.toHits(documents, question, 5);

        // 6.记录命中流水（对话引用来源 ASK），失败不影响主流程
        try {
            knowledgeHitLogService.recordHits(citations, question,
                    KnowledgeHitLogService.SOURCE_ASK, getCurrentUsername());
        } catch (Exception e) {
            log.warn("记录问答命中流水失败", e);
        }

        return AskResponse.builder()
                .sessionId(sessionId)
                .question(question)
                .answer(answer)
                .citations(citations)
                .build();
    }

    /**
     * 根据问题生成答案 流式问答：逐字返回答案（兼容入口：仅返回文本流）
     * @param sessionId 会话ID
     * @param question 问题
     * @return 答案
     */
    public Flux<String> askStream(String sessionId, String question) {
        return askStreamWithCitations(sessionId, question)
                .map(ServerSentEvent::data)
                .map(String::valueOf);
    }

    /**
     * 根据问题生成答案 流式问答：先推送 citations 事件，再逐字返回答案
     * SSE 事件：
     *   - event=citations, data=引用列表 JSON（首个事件）
     *   - 默认事件（onmessage）：答案 token
     *   - 最后追加 data=[DONE]
     * @param sessionId 会话ID
     * @param question 问题
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> askStreamWithCitations(String sessionId, String question) {
        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        return Flux.defer(() -> {
            snapshot.restore();
            return doAskStreamWithCitations(sessionId, question);
        }).doFinally(signal -> snapshot.clear());
    }

    /**
     * 在恢复的请求上下文中构建 SSE 流。
     */
    private Flux<ServerSentEvent<String>> doAskStreamWithCitations(String sessionId, String question) {
        // 1. 拿当前用户名
        String username = getCurrentUsername();

        // 2. 确保会话存在（同步）
        if (username != null) {
            try {
                conversationService.ensureConversation(sessionId, username, question);
                conversationService.saveUserMessage(sessionId, question);
            } catch (Exception e) {
                log.error("保存会话/用户消息失败", e);
            }
        }

        // 3.RAG 检索
        List<Document> documents = hybridRetriever.retrieve(question, 5);
        String context = buildContext(documents);
        List<RetrievalHitResponse> citations = knowledgeRetrievalService.toHits(documents, question, 5);

        // 3.1 记录命中流水（对话引用来源 ASK），失败不影响主流程
        try {
            knowledgeHitLogService.recordHits(citations, question, KnowledgeHitLogService.SOURCE_ASK, username);
        } catch (Exception e) {
            log.warn("记录流式问答命中流水失败", e);
        }
        String citationsJson = toJson(citations);

        // 4.拼接 System Prompt
        String systemPrompt = new PromptTemplate(SYSTEM_PROMPT).render(Map.of("context", context));

        // 5.流式调用：token 事件（答案先出）→ citations 事件（知识来源最后附上）→ [DONE]
        StringBuilder answerBuffer = new StringBuilder();
        Flux<ServerSentEvent<String>> citationsEvent = Flux.just(
                ServerSentEvent.builder("").event("citations").data(citationsJson).build());
        Flux<ServerSentEvent<String>> tokenEvents = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)) // 指定会话ID，用于聊天记忆
                .tools(healthCheckTools, metricQueryTools, metricDiscoveryTools, serviceDiscoveryTools)
                .stream()
                .content()
                .map(token -> ServerSentEvent.builder(token).build())      // 默认 message 事件
                .doOnNext(ev -> answerBuffer.append(String.valueOf(ev.data())));
        Flux<ServerSentEvent<String>> doneEvent = Flux.just(
                ServerSentEvent.builder("[DONE]").build());

        // 答案流式输出完成后，再发送 citations（知识来源），最后 [DONE]
        return Flux.concat(tokenEvents, citationsEvent, doneEvent)
                .doFinally(signal -> {
                    // 无论完成、出错、取消，都保存
                    String answer = answerBuffer.toString();
                    if (username != null && !answer.isEmpty()) {
                        try {
                            conversationService.saveAssistantMessage(sessionId, answer);
                        } catch (Exception e) {
                            log.error("保存 AI 消息失败", e);
                        }
                    }
                });
    }

    /**
     * 从 SecurityContext 拿当前用户名
     */
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return null;
    }

    /**
     * 拼接检索上下文
     */
    private String buildContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "（知识库中未找到相关内容，请基于工具查询或已有知识回答）";
        }
        return documents.stream()
                .map(d -> "【来源：" + d.getMetadata().getOrDefault("source", "未知文档") + "】\n"
                        + Objects.requireNonNullElse(d.getText(), ""))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 引用列表转 JSON 字符串
     */
    private String toJson(List<RetrievalHitResponse> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (JsonProcessingException e) {
            log.warn("citations 序列化失败", e);
            return "[]";
        }
    }

}
