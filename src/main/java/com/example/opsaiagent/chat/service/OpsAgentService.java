package com.example.opsaiagent.chat.service;

import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jMemoryId;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsAssistant;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsStreamingAssistant;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.rbac.context.RbacContextSnapshot;
import com.example.opsaiagent.security.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.concurrent.atomic.AtomicReference;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * RAG服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAgentService {

    private final HybridRetriever hybridRetriever;
    private final ConversationService conversationService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeHitLogService knowledgeHitLogService;
    private final ObjectMapper objectMapper;

    private final OpsAssistant langChain4jOpsAssistant;
    private final OpsStreamingAssistant langChain4jOpsStreamingAssistant;
    private final ChatMemoryStore langChain4jMemoryStore;

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

        List<Document> documents = hybridRetriever.retrieve(question, 5);

        // 打印召回的片段
        documents.forEach(d -> log.info("召回片段[来源={}][距离={}]: {}",
                d.getMetadata().get("source"),
                d.getMetadata().get("distance"),
                Objects.requireNonNull(d.getText()).substring(0, Math.min(150, d.getText().length()))));

        String username = SecurityUtils.getCurrentUsername();
        String memoryUsername = username == null ? "anonymous-" + UUID.randomUUID() : username;
        String answer = langChain4jOpsAssistant.ask(LangChain4jMemoryId.of(memoryUsername, sessionId),
                question, SYSTEM_PROMPT.replace("{context}", ""));
        log.info("RAG 问答完成，召回 {} 个片段", documents.size());

        // 5.构建结构化引用
        List<RetrievalHitResponse> citations = knowledgeRetrievalService.toHits(documents, question, 5);

        // 6.记录命中流水（对话引用来源 ASK），失败不影响主流程
        try {
            knowledgeHitLogService.recordHits(citations, question,
                    KnowledgeHitLogService.SOURCE_ASK, SecurityUtils.getCurrentUsername());
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
     * 根据问题生成答案，逐字返回答案后推送引用。
     * SSE 事件：
     *   - 默认事件（onmessage）：答案 token
     *   - event=citations, data=引用列表 JSON
     *   - 最后追加 data=[DONE]
     * @param sessionId 会话ID
     * @param question 问题
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> askStreamWithCitations(String sessionId, String question) {
        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        return Flux.defer(() -> {
            RbacContextSnapshot previous = RbacContextSnapshot.capture();
            snapshot.restore();
            try {
                return doAskStreamWithCitations(sessionId, question);
            } finally {
                previous.restore();
            }
        });
    }

    /**
     * 在恢复的请求上下文中构建 SSE 流。
     * @param sessionId 会话 ID
     * @param question 用户问题
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> doAskStreamWithCitations(String sessionId, String question) {
        // 1. 拿当前用户名
        String username = SecurityUtils.getCurrentUsername();

        // 2. 确保会话存在（同步）
        if (username != null) {
            try {
                // 必须在 saveUserMessage 之前回填，避免当前问题被当成历史重复注入
                langChain4jMemoryStore.getMessages(LangChain4jMemoryId.of(username, sessionId));
                conversationService.ensureConversation(sessionId, username, question);
                conversationService.saveUserMessage(sessionId, question);
            } catch (Exception e) {
                log.error("保存会话/用户消息失败", e);
            }
        }

        // 3.RAG 检索
        List<Document> documents = hybridRetriever.retrieve(question, 5);

        List<RetrievalHitResponse> citations = knowledgeRetrievalService.toHits(documents, question, 5);

        // 3.1 记录命中流水（对话引用来源 ASK），失败不影响主流程
        try {
            knowledgeHitLogService.recordHits(citations, question, KnowledgeHitLogService.SOURCE_ASK, username);
        } catch (Exception e) {
            log.warn("记录流式问答命中流水失败", e);
        }
        String citationsJson = toJson(citations);

        return streamWithLangChain4j(sessionId, question, username, citationsJson,
                SYSTEM_PROMPT.replace("{context}", ""));
    }

    /**
     * 使用 LangChain4j 流式 Agent 生成 SSE 事件。
     *
     * @param sessionId 会话 ID
     * @param question 用户问题
     * @param username 当前用户名
     * @param citationsJson 引用 JSON
     * @param systemPrompt 系统提示词
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> streamWithLangChain4j(String sessionId, String question,
                                                                  String username, String citationsJson,
                                                                  String systemPrompt) {
        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        String memoryUsername = username == null ? "anonymous-" + UUID.randomUUID() : username;
        StringBuilder answerBuffer = new StringBuilder();
        Flux<String> tokens = Flux.create(sink -> {
            AtomicReference<StreamingHandle> handle = new AtomicReference<>();
            sink.onCancel(() -> {
                StreamingHandle current = handle.get();
                if (current != null) {
                    current.cancel();
                }
            });
            RbacContextSnapshot previous = RbacContextSnapshot.capture();
            snapshot.restore();
            try {
                TokenStream stream = langChain4jOpsStreamingAssistant.askStream(
                        LangChain4jMemoryId.of(memoryUsername, sessionId), question, systemPrompt);
                stream.onPartialResponseWithContext((response, context) -> {
                            handle.set(context.streamingHandle());
                            if (sink.isCancelled()) {
                                context.streamingHandle().cancel();
                            } else {
                                sink.next(response.text());
                            }
                        })
                        .onPartialToolCallWithContext((tool, context) -> {
                            handle.set(context.streamingHandle());
                            if (sink.isCancelled()) {
                                context.streamingHandle().cancel();
                            }
                        })
                        .beforeToolExecution(tool -> {
                            if (sink.isCancelled()) {
                                throw new java.util.concurrent.CancellationException("客户端已取消问答");
                            }
                        })
                        .onCompleteResponse(response -> sink.complete())
                        .onError(sink::error)
                        .start();
            } catch (Exception e) {
                sink.error(e);
            } finally {
                previous.restore();
            }
        });
        Flux<ServerSentEvent<String>> tokenEvents = tokens
                .doOnNext(answerBuffer::append)
                .map(token -> ServerSentEvent.builder(token).build());
        Flux<ServerSentEvent<String>> tailEvents = Flux.just(
                ServerSentEvent.builder("").event("citations").data(citationsJson).build(),
                ServerSentEvent.builder("[DONE]").build());
        return Flux.concat(tokenEvents, tailEvents)
                .publishOn(Schedulers.boundedElastic(), true, 256)
                .doFinally(signal -> {
                    String answer = answerBuffer.toString();
                    if (username != null && !answer.isEmpty()) {
                        try {
                            RbacContextSnapshot previous = RbacContextSnapshot.capture();
                            try {
                                snapshot.restore();
                                conversationService.saveAssistantMessage(sessionId, answer);
                            } finally {
                                previous.restore();
                            }
                        } catch (Exception e) {
                            log.error("保存 AI 消息失败", e);
                        }
                    }
                });
    }

    /**
     * 引用列表转 JSON 字符串
     * @param citations 引用列表
     * @return 引用 JSON，序列化失败时返回空数组
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
