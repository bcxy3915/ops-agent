package com.example.opsaiagent.service;

import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsAssistant;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsStreamingAssistant;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.chat.service.OpsAgentService;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpsAgentService 单元测试
 * 覆盖：ask / askWithCitations / askStream / askStreamWithCitations / 会话保存 / 检索空场景 / 异常兜底
 * 使用可控的 LangChain4j Agent 和流式回调夹具。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 服务")
class OpsAgentServiceTest {

    /**
     * 验证无效输入在任何检索、持久化和模型操作前被拒绝。
     * @return 无返回值
     */
    @Test
    void invalidInputCannotReachModelOrPersistence() {
        for (String question : new String[]{null, "", "  "}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("session", question))
                    .isInstanceOf(com.example.opsaiagent.exception.BusinessException.class);
            StepVerifier.create(service.askStreamWithCitations("session", question))
                    .expectError(com.example.opsaiagent.exception.BusinessException.class).verify();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("s".repeat(65), "问题"))
                .isInstanceOf(com.example.opsaiagent.exception.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(conversationService, memoryStore,
                hybridRetriever, assistant, streamingAssistant);
    }

    @Mock
    private OpsAssistant assistant;

    @Mock
    private OpsStreamingAssistant streamingAssistant;

    @Mock
    private HybridRetriever hybridRetriever;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ChatMemoryStore memoryStore;

    @Mock
    private KnowledgeRetrievalService knowledgeRetrievalService;

    @Mock
    private KnowledgeHitLogService knowledgeHitLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpsAgentService service;

    @BeforeEach
    void setUp() {
        service = new OpsAgentService(hybridRetriever, conversationService,
                knowledgeRetrievalService, knowledgeHitLogService, objectMapper,
                assistant, streamingAssistant, memoryStore);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 辅助 ====================

    private Document doc(String text) {
        return new Document(text, Map.of("source", "test.md"));
    }

    private void mockAuthenticated() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("admin", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void mockAskChain(String answer) {
        when(assistant.ask(anyString(), anyString(), anyString())).thenReturn(answer);
    }

    /**
     * 将测试 token 流适配为 LangChain4j 回调，覆盖成功、异常和未完成响应。
     * @param tokens 模拟模型 token
     * @return 无返回值
     */
    private void mockStreamChain(Flux<String> tokens) {
        var stream = org.mockito.Mockito.mock(dev.langchain4j.service.TokenStream.class,
                org.mockito.Answers.RETURNS_SELF);
        var partial = new java.util.concurrent.atomic.AtomicReference<java.util.function.BiConsumer<
                dev.langchain4j.model.chat.response.PartialResponse,
                dev.langchain4j.model.chat.response.PartialResponseContext>>();
        var done = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<
                dev.langchain4j.model.chat.response.ChatResponse>>();
        var error = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Throwable>>();
        org.mockito.Mockito.doAnswer(call -> { partial.set(call.getArgument(0)); return stream; })
                .when(stream).onPartialResponseWithContext(any());
        org.mockito.Mockito.doAnswer(call -> { done.set(call.getArgument(0)); return stream; })
                .when(stream).onCompleteResponse(any());
        org.mockito.Mockito.doAnswer(call -> { error.set(call.getArgument(0)); return stream; })
                .when(stream).onError(any());
        org.mockito.Mockito.doAnswer(call -> {
            var handle = org.mockito.Mockito.mock(dev.langchain4j.model.chat.response.StreamingHandle.class);
            tokens.subscribe(token -> partial.get().accept(
                            new dev.langchain4j.model.chat.response.PartialResponse(token),
                            new dev.langchain4j.model.chat.response.PartialResponseContext(handle)),
                    failure -> error.get().accept(failure), () -> done.get().accept(null));
            return null;
        }).when(stream).start();
        when(streamingAssistant.askStream(anyString(), anyString(), anyString())).thenReturn(stream);
    }

    private RetrievalHitResponse hit(Long id, String title) {
        return RetrievalHitResponse.builder()
                .docId(id).title(title).layer("BUILTIN").trustStatus("VERIFIED")
                .version(1).source("ops-manual.md").section("简介").chunkId(1L)
                .snippet("片段").score(0.8)
                .build();
    }

    // ==================== ask ====================

    /**
     * 验证同步入口在当前问题落库前回填历史，并保存本轮答案。
     * @return 无返回值
     */
    @Test
    void synchronousChatPersistsMessagesAfterLoadingHistory() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockAskChain("同步回答");

        service.askWithCitations("sess-1", "问题");

        var order = org.mockito.Mockito.inOrder(conversationService, memoryStore, assistant);
        order.verify(conversationService).ensureConversation("sess-1", "admin", "问题");
        order.verify(memoryStore).getMessages(
                com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jMemoryId.of("admin", "sess-1"));
        order.verify(conversationService).saveUserMessage("sess-1", "问题");
        order.verify(assistant).ask(anyString(), eq("问题"), anyString());
        order.verify(conversationService).saveAssistantMessage("sess-1", "同步回答");
    }

    /**
     * 验证同步和流式入口均在检索、记忆及模型调用前拒绝越权会话。
     * @return 无返回值
     */
    @Test
    void rejectsForeignSessionBeforeRetrievalOrModelInvocation() {
        mockAuthenticated();
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("无权访问会话"))
                .when(conversationService).ensureConversation("foreign", "admin", "问题");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("foreign", "问题"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        StepVerifier.create(service.askStreamWithCitations("foreign", "问题"))
                .expectError(org.springframework.security.access.AccessDeniedException.class).verify();

        org.mockito.Mockito.verifyNoInteractions(assistant, streamingAssistant, memoryStore, hybridRetriever);
        verify(conversationService, never()).saveUserMessage(anyString(), anyString());
        verify(conversationService, never()).saveAssistantMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("ask：检索到文档，返回 LLM 回答")
    void ask_withDocuments() {
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("这是知识库内容")));
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockAskChain("模拟回答");

        String result = service.ask("sess-1", "todo-service 健康吗");

        assertThat(result).isEqualTo("模拟回答");
    }

    @Test
    @DisplayName("ask：知识库为空，构造空上下文提示")
    void ask_emptyDocuments() {
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockAskChain("回答");

        String result = service.ask("sess-1", "随便问");

        assertThat(result).isEqualTo("回答");
    }

    // ==================== askWithCitations ====================

    @Test
    @DisplayName("askWithCitations：返回答案与结构化引用")
    void askWithCitations_withHits() {
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("知识库内容")));
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt()))
                .thenReturn(List.of(hit(1L, "运维手册")));
        mockAskChain("带引用的回答");

        AskResponse r = service.askWithCitations("sess-1", "手册怎么用");

        assertThat(r.getAnswer()).isEqualTo("带引用的回答");
        assertThat(r.getSessionId()).isEqualTo("sess-1");
        assertThat(r.getQuestion()).isEqualTo("手册怎么用");
        assertThat(r.getCitations()).hasSize(1);
        assertThat(r.getCitations().get(0).getDocId()).isEqualTo(1L);
        assertThat(r.getCitations().get(0).getTitle()).isEqualTo("运维手册");
        assertThat(r.getCitations().get(0).getTrustStatus()).isEqualTo("VERIFIED");
        assertThat(r.getCitations().get(0).getScore()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("askWithCitations：无命中时 citations 为空列表")
    void askWithCitations_empty() {
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockAskChain("无引用回答");

        AskResponse r = service.askWithCitations("sess-1", "问题");

        assertThat(r.getAnswer()).isEqualTo("无引用回答");
        assertThat(r.getCitations()).isEmpty();
    }

    // ==================== askStream ====================

    @Test
    @DisplayName("askStream：答案后推送 citations，登录用户保存会话与消息")
    void askStream_authenticated() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("内容")));
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt()))
                .thenReturn(List.of(hit(1L, "运维手册")));
        mockStreamChain(Flux.just("你", "好"));

        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
                .expectNext("你", "好")
                .expectNextMatches(s -> s.contains("\"title\":\"运维手册\"") && s.contains("\"docId\":1"))
                .expectNext("[DONE]")
                .verifyComplete();

        // 会话和消息都应保存（doFinally 在 boundedElastic 线程异步执行，加 timeout 等待避免竞态）
        verify(conversationService).ensureConversation("sess-1", "admin", "问题");
        verify(conversationService).saveUserMessage("sess-1", "问题");
        verify(conversationService, timeout(1000)).saveAssistantMessage("sess-1", "你好");
    }

    @Test
    @DisplayName("askStream：未登录，不保存会话")
    void askStream_unauthenticated() {
        // 不设置 SecurityContext
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.just("回答"));

        Flux<String> flux = service.askStream("sess-anon", "问题");

        StepVerifier.create(flux)
                .expectNext("回答")
                .expectNext("[]", "[DONE]")
                .verifyComplete();

        verify(conversationService, never()).ensureConversation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("askStream：保存会话失败时终止，不调用模型或写入消息")
    void askStream_saveConversationFails() {
        mockAuthenticated();
        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(conversationService).ensureConversation(anyString(), anyString(), anyString());
        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
                .expectErrorMessage("DB error")
                .verify();
        org.mockito.Mockito.verifyNoInteractions(streamingAssistant, hybridRetriever, memoryStore);
        verify(conversationService, never()).saveUserMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("askStream：LLM 流异常，doFinally 仍触发但不保存空答案")
    void askStream_streamError() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.error(new RuntimeException("LLM error")));

        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
                .expectError()
                .verify();

        // 空答案，不应保存
        verify(conversationService, never()).saveAssistantMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("流式取消：保存已输出的部分答案，不发送完成标记")
    void askStream_cancelPersistsPartialAnswer() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.concat(Flux.just("已获取的部分答案"), Flux.never()));

        StepVerifier.create(service.askStreamWithCitations("sess-1", "检查服务"))
                .expectNextMatches(event -> event.event() == null
                        && "已获取的部分答案".equals(event.data()))
                .thenCancel()
                .verify(java.time.Duration.ofSeconds(5));

        verify(conversationService, timeout(1000))
                .saveAssistantMessage("sess-1", "已获取的部分答案");
    }

    // ==================== askStreamWithCitations ====================

    @Test
    @DisplayName("askStreamWithCitations：答案、citations、DONE 依次输出")
    void askStreamWithCitations_eventOrder() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("内容")));
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt()))
                .thenReturn(List.of(hit(1L, "运维手册")));
        mockStreamChain(Flux.just("你", "好"));

        Flux<ServerSentEvent<String>> flux = service.askStreamWithCitations("sess-1", "问题");

        StepVerifier.create(flux)
                .expectNextMatches(ev -> ev.event() == null && "你".equals(ev.data()))
                .expectNextMatches(ev -> ev.event() == null && "好".equals(ev.data()))
                .expectNextMatches(ev -> "citations".equals(ev.event())
                        && ev.data().contains("\"title\":\"运维手册\""))
                .expectNextMatches(ev -> "[DONE]".equals(ev.data()))
                .verifyComplete();

        verify(conversationService).ensureConversation("sess-1", "admin", "问题");
        verify(conversationService).saveUserMessage("sess-1", "问题");
        // doFinally 在 boundedElastic 线程异步执行，加 timeout 等待避免竞态
        verify(conversationService, timeout(1000)).saveAssistantMessage("sess-1", "你好");
    }

    @Test
    @DisplayName("askStreamWithCitations：无命中时 citations 事件数据为 []")
    void askStreamWithCitations_emptyCitations() {
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.just("回答"));

        Flux<ServerSentEvent<String>> flux = service.askStreamWithCitations("sess-anon", "问题");

        StepVerifier.create(flux)
                .expectNextMatches(ev -> "回答".equals(ev.data()))
                .expectNextMatches(ev -> "citations".equals(ev.event()) && "[]".equals(ev.data()))
                .expectNextMatches(ev -> "[DONE]".equals(ev.data()))
                .verifyComplete();
    }
    /**
     * 创建可控的 LangChain4j 流式测试夹具。
     * @param complete 是否正常结束
     * @param failure 是否模拟错误
     * @param handle 模型取消句柄
     * @return 流式测试夹具
     */
    private dev.langchain4j.service.TokenStream langChainStream(boolean complete, boolean failure,
            dev.langchain4j.model.chat.response.StreamingHandle handle) {
        var stream = org.mockito.Mockito.mock(dev.langchain4j.service.TokenStream.class,
                org.mockito.Answers.RETURNS_SELF);
        java.util.concurrent.atomic.AtomicReference<java.util.function.BiConsumer<
                dev.langchain4j.model.chat.response.PartialResponse,
                dev.langchain4j.model.chat.response.PartialResponseContext>> partial =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<
                dev.langchain4j.model.chat.response.ChatResponse>> done =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Throwable>> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(call -> { partial.set(call.getArgument(0)); return stream; })
                .when(stream).onPartialResponseWithContext(any());
        org.mockito.Mockito.doAnswer(call -> { done.set(call.getArgument(0)); return stream; })
                .when(stream).onCompleteResponse(any());
        org.mockito.Mockito.doAnswer(call -> { error.set(call.getArgument(0)); return stream; })
                .when(stream).onError(any());
        org.mockito.Mockito.doAnswer(call -> {
            partial.get().accept(new dev.langchain4j.model.chat.response.PartialResponse("回答"),
                    new dev.langchain4j.model.chat.response.PartialResponseContext(handle));
            if (failure) { error.get().accept(new IllegalStateException("模型失败")); }
            else if (complete) { done.get().accept(null); }
            return null;
        }).when(stream).start();
        when(streamingAssistant.askStream(anyString(), anyString(), anyString())).thenReturn(stream);
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        return stream;
    }

    /**
     * 验证流式事件顺序和历史回填先于当前问题落库。
     * @return 无返回值
     */
    @Test
    @DisplayName("LangChain4j 流按答案、引用、DONE 输出并在保存当前问题前回填记忆")
    void langChainStreamPreservesContractAndHistoryOrder() {
        mockAuthenticated();
        var store = memoryStore;

        langChainStream(true, false, org.mockito.Mockito.mock(
                dev.langchain4j.model.chat.response.StreamingHandle.class));
        StepVerifier.create(service.askStreamWithCitations("lc-session", "问题"))
                .expectNextMatches(ev -> "回答".equals(ev.data()))
                .expectNextMatches(ev -> "citations".equals(ev.event()) && "[]".equals(ev.data()))
                .expectNextMatches(ev -> "[DONE]".equals(ev.data()))
                .verifyComplete();
        var order = org.mockito.Mockito.inOrder(store, conversationService);
        order.verify(store).getMessages(
                com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jMemoryId.of("admin", "lc-session"));
        order.verify(conversationService).saveUserMessage("lc-session", "问题");
        verify(conversationService, timeout(1000)).saveAssistantMessage("lc-session", "回答");

    }

    /**
     * 验证模型错误不会输出成功结束事件。
     * @return 无返回值
     */
    @Test
    @DisplayName("LangChain4j 模型失败保留部分答案且不伪造引用和 DONE")
    void langChainStreamFailureHasNoSuccessTail() {
        mockAuthenticated();
        langChainStream(false, true, org.mockito.Mockito.mock(
                dev.langchain4j.model.chat.response.StreamingHandle.class));
        StepVerifier.create(service.askStreamWithCitations("lc-session", "问题"))
                .expectNextMatches(ev -> "回答".equals(ev.data()))
                .expectErrorMessage("模型失败").verify();
        verify(conversationService, timeout(1000)).saveAssistantMessage("lc-session", "回答");
    }

    /**
     * 验证客户端取消会传播到模型并保存部分答案。
     * @return 无返回值
     */
    @Test
    @DisplayName("LangChain4j 客户端取消会取消模型并保存部分答案")
    void langChainStreamCancellationStopsModel() {
        mockAuthenticated();
        var handle = org.mockito.Mockito.mock(dev.langchain4j.model.chat.response.StreamingHandle.class);
        langChainStream(false, false, handle);
        StepVerifier.create(service.askStreamWithCitations("lc-session", "问题"))
                .expectNextMatches(ev -> "回答".equals(ev.data())).thenCancel().verify();
        verify(handle, timeout(1000)).cancel();
        verify(conversationService, timeout(1000)).saveAssistantMessage("lc-session", "回答");
    }}
