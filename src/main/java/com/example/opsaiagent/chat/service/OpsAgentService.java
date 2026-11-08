package com.example.opsaiagent.chat.service;

import com.example.opsaiagent.ai.adapter.langchain4j.ConversationChatMemoryStore;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsAssistant;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsStreamingAssistant;
import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.rbac.context.RbacContextSnapshot;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.response.StreamingHandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 使用持久化轮次令牌、一次检索快照和受控记忆执行问答。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAgentService {
    private final HybridRetriever hybridRetriever;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeHitLogService knowledgeHitLogService;
    private final ObjectMapper objectMapper;
    private final OpsAssistant langChain4jOpsAssistant;
    private final OpsStreamingAssistant langChain4jOpsStreamingAssistant;
    private final ConversationChatMemoryStore memoryStore;
    private final ChatTurnService turns;
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
     * 同步问答的纯文本兼容入口。
     * @param sessionId 会话标识
     * @param question 用户问题
     * @return 完整答案
     */
    public String ask(String sessionId, String question) {
        return askWithCitations(sessionId, question).getAnswer();
    }

    /**
     * 同步执行一轮问答，答案落库后才释放跨实例互斥。
     * @param sessionId 会话标识
     * @param question 用户问题
     * @return 答案和本轮相同检索快照的引用
     */
    public AskResponse askWithCitations(String sessionId, String question) {
        try (Round round = begin(sessionId, question)) {
            List<RetrievalHitResponse> citations = retrieve(round, question);
            String answer = langChain4jOpsAssistant.ask(round.turn.memoryId(), question,
                    SYSTEM_PROMPT.replace("{context}", ""));
            if (answer == null || answer.isBlank()) { throw new IllegalStateException("模型未返回答案"); }
            round.answer.append(answer);
            round.finish();
            return AskResponse.builder().sessionId(sessionId).question(question).answer(answer)
                    .citations(citations).build();
        }
    }

    /**
     * 流式纯文本兼容入口。
     * @param sessionId 会话标识
     * @param question 用户问题
     * @return 答案、引用及结束标记的数据流
     */
    public Flux<String> askStream(String sessionId, String question) {
        return askStreamWithCitations(sessionId, question).map(ServerSentEvent::data);
    }

    /**
     * 每次订阅独立领取轮次，成功依次输出答案、引用和 DONE，失败或取消保存部分答案。
     * @param sessionId 会话标识
     * @param question 用户问题
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> askStreamWithCitations(String sessionId, String question) {
        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        return Flux.defer(() -> withContext(snapshot, () -> Flux.using(
                () -> begin(sessionId, question),
                round -> {
                    List<RetrievalHitResponse> citations = retrieve(round, question);
                    Flux<String> invalidation = Flux.interval(Duration.ofSeconds(1))
                            .publishOn(Schedulers.boundedElastic())
                            .handle((tick, sink) -> {
                                try { turns.assertActive(round.turn); }
                                catch (Exception failure) { sink.error(failure); }
                            });
                    return tokens(round, question)
                            .takeUntilOther(invalidation)
                            .takeUntilOther(Mono.delay(turns.timeout()).flatMap(tick ->
                                    Mono.error(new java.util.concurrent.TimeoutException("本轮问答超过最长时间"))))
                            .doOnNext(round.answer::append)
                            .map(token -> ServerSentEvent.builder(token).build())
                            .concatWith(Flux.defer(() -> withContext(snapshot, () -> {
                                round.finish();
                                return Flux.just(ServerSentEvent.builder(toJson(citations)).event("citations").build(),
                                        ServerSentEvent.builder("[DONE]").build());
                            })));
                },
                round -> withContext(snapshot, () -> { round.close(); return null; }), true)));
    }

    /**
     * 验证输入、领取跨实例租约并在当前问题落库前加载数据库历史。
     * @param sessionId 会话标识
     * @param question 用户问题
     * @return 需要关闭的轮次资源
     */
    private Round begin(String sessionId, String question) {
        if (question == null || question.isBlank() || sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "问题不能为空，会话 ID 必须为 1 至 64 个字符");
        }
        Round round = new Round(turns.begin(sessionId, question));
        try {
            memoryStore.openTurn(round.turn.memoryId(), () -> turns.assertActive(round.turn));
            turns.saveQuestion(round.turn, question);
            return round;
        } catch (RuntimeException failure) {
            round.close();
            throw failure;
        }
    }

    /**
     * 检索一次并绑定模型快照，引用和命中记录从同一份文档生成。
     * @param round 当前轮次
     * @param question 用户问题
     * @return 本轮引用
     */
    private List<RetrievalHitResponse> retrieve(Round round, String question) {
        memoryStore.bindDocuments(round.turn.memoryId(), hybridRetriever.retrieve(question, 5));
        List<RetrievalHitResponse> citations = knowledgeRetrievalService.toHits(
                memoryStore.documents(round.turn.memoryId()), question, 5);
        try {
            knowledgeHitLogService.recordHits(citations, question, KnowledgeHitLogService.SOURCE_ASK, round.turn.username());
        } catch (Exception failure) { log.warn("记录问答命中流水失败", failure); }
        return citations;
    }

    /**
     * 将 LangChain4j 回调转换为可取消的 token 流，删除或过期后停止输出与后续工具。
     * @param round 当前轮次
     * @param question 用户问题
     * @return 纯答案 token
     */
    private Flux<String> tokens(Round round, String question) {
        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        return Flux.create(sink -> {
            sink.onDispose(round::cancelModel);
            try {
                withContext(snapshot, () -> {
                    var stream = langChain4jOpsStreamingAssistant.askStream(round.turn.memoryId(), question,
                            SYSTEM_PROMPT.replace("{context}", ""));
                    stream.onPartialResponseWithContext((response, context) -> {
                                round.streamingHandle.set(context.streamingHandle());
                                if (sink.isCancelled()) { round.cancelModel(); return; }
                                try {
                                    turns.assertActive(round.turn);
                                    sink.next(response.text());
                                } catch (Exception failure) { sink.error(failure); }
                            })
                            .onPartialToolCallWithContext((tool, context) -> {
                                round.streamingHandle.set(context.streamingHandle());
                                if (sink.isCancelled()) { round.cancelModel(); }
                            })
                            .beforeToolExecution(tool -> {
                                if (sink.isCancelled()) { throw new java.util.concurrent.CancellationException("客户端已取消问答"); }
                                turns.assertActive(round.turn);
                            })
                            .onCompleteResponse(response -> sink.complete()).onError(sink::error).start();
                    return null;
                });
            } catch (Exception failure) { sink.error(failure); }
        });
    }

    /**
     * 序列化引用，失败时中止响应而不伪造成功引用。
     * @param citations 引用列表
     * @return 引用 JSON
     */
    private String toJson(List<RetrievalHitResponse> citations) {
        try { return objectMapper.writeValueAsString(citations); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("引用序列化失败", failure);
        }
    }

    /**
     * 在请求上下文中执行任务，结束后还原线程原状态。
     * @param snapshot 请求权限快照
     * @param action 待执行任务
     * @param <T> 返回类型
     * @return 任务结果
     */
    private static <T> T withContext(RbacContextSnapshot snapshot, java.util.function.Supplier<T> action) {
        var previous = RbacContextSnapshot.capture();
        snapshot.restore();
        try { return action.get(); }
        finally { previous.restore(); }
    }

    /** 单次订阅持有的资源，终态只落库一次，关闭后移除两层框架记忆及项目缓存。 */
    private final class Round implements AutoCloseable {
        private final ChatTurn turn;
        private final StringBuffer answer = new StringBuffer();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
        /**
         * 绑定本轮持久化令牌。
         * @param turn 本轮令牌
         * @return 无返回值
         */
        private Round(ChatTurn turn) { this.turn = turn; }
        /**
         * 保存答案并释放本轮租约，失败时不发送成功尾事件。
         * @return 无返回值
         */
        private void finish() {
            if (finished.compareAndSet(false, true)) { turns.finish(turn, answer.toString()); }
        }
        /**
         * 原子取出并取消当前模型句柄，终态清理不依赖 Reactor 上游的取消时序。
         * @return 无返回值
         */
        private void cancelModel() {
            StreamingHandle handle = streamingHandle.getAndSet(null);
            if (handle != null) { handle.cancel(); }
        }
        /**
         * 错误或取消时尝试保存部分答案，并始终清理所有轮次资源。
         * @return 无返回值
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) { return; }
            try { cancelModel(); finish(); }
            catch (Exception failure) { log.warn("轮次结束时答案未保存: {}", failure.getMessage()); }
            finally {
                try { langChain4jOpsAssistant.evictChatMemory(turn.memoryId()); }
                finally {
                    try { langChain4jOpsStreamingAssistant.evictChatMemory(turn.memoryId()); }
                    finally {
                        memoryStore.closeTurn(turn.memoryId());
                        turns.release(turn);
                    }
                }
            }
        }
    }
}
