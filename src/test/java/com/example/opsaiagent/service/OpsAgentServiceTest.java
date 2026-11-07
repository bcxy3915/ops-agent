package com.example.opsaiagent.service;

import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.chat.entity.MessageEntity;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.chat.service.OpsAgentService;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import com.example.opsaiagent.tools.ServiceDiscoveryTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
 * ChatClient 是深链式调用，用 RETURNS_DEEP_STUBS。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 服务")
class OpsAgentServiceTest {

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @Mock
    private HealthCheckTools healthCheckTools;

    @Mock
    private MetricQueryTools metricQueryTools;

    @Mock
    private MetricDiscoveryTools metricDiscoveryTools;

    @Mock
    private ServiceDiscoveryTools serviceDiscoveryTools;

    @Mock
    private HybridRetriever hybridRetriever;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private KnowledgeRetrievalService knowledgeRetrievalService;

    @Mock
    private KnowledgeHitLogService knowledgeHitLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpsAgentService service;

    @BeforeEach
    void setUp() {
        service = new OpsAgentService(
                chatClient, chatMemory, healthCheckTools, metricQueryTools,
                metricDiscoveryTools, serviceDiscoveryTools,
                hybridRetriever, conversationService,
                knowledgeRetrievalService, knowledgeHitLogService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 辅助 ====================

    private Document doc(String text) {
        // Spring AI 1.1.0 需要 metadata 非 null
        return new Document(text, Map.of("source", "test.md"));
    }

    private void mockAuthenticated() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("admin", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void mockAskChain(String answer) {
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .call().content()).thenReturn(answer);
    }

    private void mockStreamChain(Flux<String> tokens) {
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .stream().content()).thenReturn(tokens);
    }

    private MessageEntity history(Long id, String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private RetrievalHitResponse hit(Long id, String title) {
        return RetrievalHitResponse.builder()
                .docId(id).title(title).layer("BUILTIN").trustStatus("VERIFIED")
                .version(1).source("ops-manual.md").section("简介").chunkId(1L)
                .snippet("片段").score(0.8)
                .build();
    }

    // ==================== ask ====================

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
    @DisplayName("askStream：保存会话失败不中断主流程")
    void askStream_saveConversationFails() {
        mockAuthenticated();
        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(conversationService).ensureConversation(anyString(), anyString(), anyString());
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.just("A"));

        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
                .expectNext("A")
                .expectNext("[]", "[DONE]")
                .verifyComplete();
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

    // ==================== 聊天记忆回填 ====================

    @Test
    @DisplayName("askStream：内存记忆为空时从数据库回填历史")
    void askStream_warmUpMemoryWhenEmpty() {
        mockAuthenticated();
        when(chatMemory.get("sess-1")).thenReturn(List.of());
        when(conversationService.loadRecentMessages("sess-1", "admin", 20)).thenReturn(List.of(
                history(1L, "USER", "todo-service 健康吗"),
                history(2L, "ASSISTANT", "当前健康状态为 UP")));
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.just("好"));

        StepVerifier.create(service.askStream("sess-1", "再次检查一下"))
                .expectNext("好", "[]", "[DONE]")
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq("sess-1"), captor.capture());
        List<Message> history = captor.getValue();
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).isInstanceOf(UserMessage.class);
        assertThat(history.get(0).getText()).isEqualTo("todo-service 健康吗");
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(history.get(1).getText()).isEqualTo("当前健康状态为 UP");
    }

    @Test
    @DisplayName("askStream：内存记忆非空时不回填")
    void askStream_skipWarmUpWhenMemoryNotEmpty() {
        mockAuthenticated();
        when(chatMemory.get("sess-1")).thenReturn(List.of(new UserMessage("已有历史")));
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.just("好"));

        StepVerifier.create(service.askStream("sess-1", "问题"))
                .expectNext("好", "[]", "[DONE]")
                .verifyComplete();

        verify(conversationService, never()).loadRecentMessages(anyString(), anyString(), anyInt());
        verify(chatMemory, never()).add(anyString(), anyList());
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
        var assistant = org.mockito.Mockito.mock(
                com.example.opsaiagent.ai.adapter.langchain4j.OpsStreamingAssistant.class);
        when(assistant.askStream(anyString(), anyString(), anyString())).thenReturn(stream);
        service.setLangChain4jOpsStreamingAssistant(assistant);
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
        var store = org.mockito.Mockito.mock(dev.langchain4j.store.memory.chat.ChatMemoryStore.class);
        service.setLangChain4jMemoryStore(store);
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
        verify(chatMemory, never()).get(anyString());
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
