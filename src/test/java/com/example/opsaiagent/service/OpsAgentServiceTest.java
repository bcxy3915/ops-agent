package com.example.opsaiagent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import com.example.opsaiagent.ai.adapter.langchain4j.*;
import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.chat.service.*;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.service.*;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用可控模型验证轮次互斥入口、快照、持久化、取消和资源回收，不访问外部服务。 */
class OpsAgentServiceTest {
    /**
     * 通过真实 LangChain4j 编排验证模型收到的正文与引用来自同一快照，框架不再次检索。
     * @return 无返回值
     */
    @Test
    void realLangChain4jUsesExactlyTheCitationSnapshot() {
        var conversations = mock(ConversationService.class);
        var store = new ConversationChatMemoryStore(conversations, 20);
        String memoryId = LangChain4jMemoryId.of("alice", "session") + ".request";
        var actualTurn = new ChatTurn("tenant", "session", "alice", "request", "conversation", memoryId);
        when(turns.begin("session", "问题")).thenReturn(actualTurn);
        Document document = new Document("同一份故障处理正文", Map.of("source", "runbook"));
        when(retriever.retrieve("问题", 5)).thenReturn(List.of(document));
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            var request = (ChatRequest) call.getArgument(0);
            assertThat(request.messages().toString()).contains(document.getText());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("真实编排的模拟答案")).build();
        });
        OpsAssistant realAssistant = AiServices.builder(OpsAssistant.class)
                .chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id).maxMessages(20).chatMemoryStore(store).build())
                .contentRetriever(new LangChain4jKnowledgeContentRetriever(retriever, 5, store))
                .storeRetrievedContentInChatMemory(false).build();
        var actual = new OpsAgentService(retriever, references, hits, new ObjectMapper(),
                realAssistant, streaming, store, turns);
        assertThat(actual.ask("session", "问题")).isEqualTo("真实编排的模拟答案");
        verify(retriever, times(1)).retrieve(anyString(), anyInt());
        verify(references).toHits(eq(List.of(document)), eq("问题"), eq(5));
        assertThat(store.size()).isZero();
    }
    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final KnowledgeRetrievalService references = mock(KnowledgeRetrievalService.class);
    private final KnowledgeHitLogService hits = mock(KnowledgeHitLogService.class);
    private final OpsAssistant assistant = mock(OpsAssistant.class);
    private final OpsStreamingAssistant streaming = mock(OpsStreamingAssistant.class);
    private final ConversationChatMemoryStore memory = mock(ConversationChatMemoryStore.class);
    private final ChatTurnService turns = mock(ChatTurnService.class);
    private final ChatTurn turn = new ChatTurn("tenant", "session", "alice", "request", "conversation", "..session.request");
    private final StreamingHandle handle = mock(StreamingHandle.class);
    private OpsAgentService service;

    /**
     * 创建仅在实际调用时提供响应的夹具，避免无关分支需要多余模拟。
     * @return 无返回值
     */
    @BeforeEach
    void setup() {
        service = new OpsAgentService(retriever, references, hits, new ObjectMapper(), assistant, streaming, memory, turns);
        lenient().when(turns.begin("session", "问题")).thenReturn(turn);
        lenient().when(turns.timeout()).thenReturn(Duration.ofSeconds(10));
        lenient().when(memory.documents(turn.memoryId())).thenReturn(List.of(new Document("本轮快照")));
        lenient().when(assistant.ask(anyString(), anyString(), anyString())).thenReturn("答案");
    }

    /**
     * 验证同步问答只检索一次，历史先加载，问题先保存，答案提交后回收两层缓存。
     * @return 无返回值
     */
    @Test
    void syncUsesOneSnapshotAndPersistsBeforeEviction() {
        AskResponse response = service.askWithCitations("session", "问题");
        assertThat(response.getAnswer()).isEqualTo("答案");
        var order = inOrder(turns, memory, retriever, assistant, streaming);
        order.verify(turns).begin("session", "问题");
        order.verify(memory).openTurn(eq(turn.memoryId()), any());
        order.verify(turns).saveQuestion(turn, "问题");
        order.verify(retriever).retrieve("问题", 5);
        order.verify(assistant).ask(eq(turn.memoryId()), eq("问题"), anyString());
        order.verify(turns).finish(turn, "答案");
        order.verify(assistant).evictChatMemory(turn.memoryId());
        order.verify(streaming).evictChatMemory(turn.memoryId());
        order.verify(memory).closeTurn(turn.memoryId());
        verify(retriever, times(1)).retrieve(anyString(), anyInt());
    }

    /**
     * 验证重复请求被拒绝时不访问记忆、检索或模型。
     * @return 无返回值
     */
    @Test
    void busyTurnCannotStartModelOrRetrieval() {
        when(turns.begin("session", "问题")).thenThrow(new IllegalStateException("会话正在回答"));
        assertThatThrownBy(() -> service.ask("session", "问题")).hasMessage("会话正在回答");
        verifyNoInteractions(memory, retriever, assistant, streaming);
    }

    /**
     * 验证用户消息保存失败时释放租约与记忆，模型不开始执行。
     * @return 无返回值
     */
    @Test
    void preparationFailureCleansResources() {
        doThrow(new IllegalStateException("保存失败")).when(turns).saveQuestion(turn, "问题");
        assertThatThrownBy(() -> service.ask("session", "问题")).hasMessage("保存失败");
        verify(memory).closeTurn(turn.memoryId());
        verify(turns).release(turn);
        verifyNoInteractions(retriever);
        verify(assistant, never()).ask(anyString(), anyString(), anyString());
    }

    /**
     * 验证无效输入在轮次领取前被拒绝。
     * @return 无返回值
     */
    @Test
    void invalidInputCannotAcquireTurn() {
        for (String question : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> service.ask("session", question)).isInstanceOf(RuntimeException.class);
            StepVerifier.create(service.askStreamWithCitations("session", question)).expectError().verify();
        }
        assertThatThrownBy(() -> service.ask("s".repeat(65), "问题")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(turns);
    }

    /**
     * 验证答案、引用及 DONE 契约，保存成功先于成功尾事件。
     * @return 无返回值
     */
    @Test
    void streamingPersistsBeforeCitationsAndDone() {
        stream(true, false);
        StepVerifier.create(service.askStreamWithCitations("session", "问题"))
                .expectNextMatches(e -> "部分答案".equals(e.data()))
                .assertNext(e -> {
                    verify(turns).finish(turn, "部分答案");
                    assertThat(e.event()).isEqualTo("citations");
                    assertThat(e.data()).isEqualTo("[]");
                }).expectNextMatches(e -> "[DONE]".equals(e.data())).verifyComplete();
        verify(memory).closeTurn(turn.memoryId());
        verify(turns, times(1)).finish(turn, "部分答案");
    }

    /**
     * 验证流式模型失败保存部分答案且不发送成功尾事件。
     * @return 无返回值
     */
    @Test
    void streamFailurePersistsPartialAnswerAndCleansUp() {
        stream(false, true);
        StepVerifier.create(service.askStreamWithCitations("session", "问题"))
                .expectNextMatches(e -> "部分答案".equals(e.data())).expectErrorMessage("模型失败").verify();
        verify(turns).finish(turn, "部分答案");
        verify(memory).closeTurn(turn.memoryId());
    }

    /**
     * 验证取消传播到底层模型，只保存已收到的答案并释放租约。
     * @return 无返回值
     */
    @Test
    void cancellationCancelsModelAndReleasesResources() {
        stream(false, false);
        StepVerifier.create(service.askStreamWithCitations("session", "问题"))
                .expectNextMatches(e -> "部分答案".equals(e.data())).thenCancel().verify();
        verify(handle, timeout(1000)).cancel();
        verify(turns).finish(turn, "部分答案");
        verify(turns).release(turn);
        verify(memory).closeTurn(turn.memoryId());
    }

    /**
     * 验证删除或令牌失效阻止输出与最终写回，失败后仍清理本机缓存。
     * @return 无返回值
     */
    @Test
    void deletedTurnCannotEmitOrWriteSuccessTail() {
        stream(true, false);
        doThrow(new IllegalStateException("会话已删除")).when(turns).assertActive(turn);
        doThrow(new IllegalStateException("旧轮次拒绝写入")).when(turns).finish(eq(turn), anyString());
        StepVerifier.create(service.askStreamWithCitations("session", "问题"))
                .expectErrorMessage("会话已删除").verify();
        verify(handle, timeout(1000)).cancel();
        verify(memory).closeTurn(turn.memoryId());
    }

    /**
     * 验证提交答案失败不能输出 citations 或 DONE。
     * @return 无返回值
     */
    @Test
    void persistenceFailureDoesNotReportSuccess() {
        stream(true, false);
        doThrow(new IllegalStateException("提交失败")).when(turns).finish(turn, "部分答案");
        StepVerifier.create(service.askStreamWithCitations("session", "问题"))
                .expectNextMatches(e -> "部分答案".equals(e.data())).expectErrorMessage("提交失败").verify();
        verify(turns, times(1)).finish(turn, "部分答案");
        verify(memory).closeTurn(turn.memoryId());
    }

    /**
     * 验证无人继续输出的模型也受整轮超时限制。
     * @return 无返回值
     */
    @Test
    void totalDeadlineCancelsStalledStream() {
        stream(false, false);
        when(turns.timeout()).thenReturn(Duration.ofMillis(30));
        StepVerifier.create(service.askStreamWithCitations("session", "问题"))
                .expectNextMatches(e -> "部分答案".equals(e.data()))
                .expectError(TimeoutException.class).verify(Duration.ofSeconds(3));
        verify(handle, timeout(1000)).cancel();
        verify(memory, timeout(1000)).closeTurn(turn.memoryId());
    }

    /**
     * 创建可控回调夹具，不访问真实模型。
     * @param complete 是否正常结束
     * @param fail 是否在首片段后失败
     * @return 无返回值
     */
    private void stream(boolean complete, boolean fail) {
        var stream = mock(TokenStream.class, RETURNS_SELF);
        AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>> partial = new AtomicReference<>();
        AtomicReference<Consumer<ChatResponse>> done = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        doAnswer(call -> { partial.set(call.getArgument(0)); return stream; }).when(stream).onPartialResponseWithContext(any());
        doAnswer(call -> { done.set(call.getArgument(0)); return stream; }).when(stream).onCompleteResponse(any());
        doAnswer(call -> { error.set(call.getArgument(0)); return stream; }).when(stream).onError(any());
        doAnswer(call -> {
            partial.get().accept(new PartialResponse("部分答案"), new PartialResponseContext(handle));
            if (fail) { error.get().accept(new IllegalStateException("模型失败")); }
            else if (complete) { done.get().accept(null); }
            return null;
        }).when(stream).start();
        when(streaming.askStream(eq(turn.memoryId()), eq("问题"), anyString())).thenReturn(stream);
    }
}
