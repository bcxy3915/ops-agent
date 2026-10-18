package com.example.opsaiagent.service;

import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
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
import static org.mockito.Mockito.never;
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
    private KnowledgeRetrievalService knowledgeRetrievalService;

    @Mock
    private KnowledgeHitLogService knowledgeHitLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpsAgentService service;

    @BeforeEach
    void setUp() {
        service = new OpsAgentService(
                chatClient, healthCheckTools, metricQueryTools,
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
    @DisplayName("askStream：先推 citations 事件再逐字输出，登录用户保存会话与消息")
    void askStream_authenticated() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("内容")));
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt()))
                .thenReturn(List.of(hit(1L, "运维手册")));
        mockStreamChain(Flux.just("你", "好"));

        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
                .expectNextMatches(s -> s.contains("\"title\":\"运维手册\"") && s.contains("\"docId\":1"))
                .expectNext("你", "好", "[DONE]")
                .verifyComplete();

        // 会话和消息都应保存
        verify(conversationService).ensureConversation("sess-1", "admin", "问题");
        verify(conversationService).saveUserMessage("sess-1", "问题");
        verify(conversationService).saveAssistantMessage("sess-1", "你好");
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
                .expectNext("[]")
                .expectNext("回答", "[DONE]")
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
                .expectNext("[]")
                .expectNext("A", "[DONE]")
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
                .expectNext("[]")
                .expectError()
                .verify();

        // 空答案，不应保存
        verify(conversationService, never()).saveAssistantMessage(anyString(), anyString());
    }

    // ==================== askStreamWithCitations ====================

    @Test
    @DisplayName("askStreamWithCitations：首个事件为 citations，含 event 名称与 JSON 数据")
    void askStreamWithCitations_eventOrder() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("内容")));
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt()))
                .thenReturn(List.of(hit(1L, "运维手册")));
        mockStreamChain(Flux.just("你", "好"));

        Flux<ServerSentEvent<String>> flux = service.askStreamWithCitations("sess-1", "问题");

        StepVerifier.create(flux)
                .expectNextMatches(ev -> "citations".equals(ev.event())
                        && ev.data().contains("\"title\":\"运维手册\""))
                .expectNextMatches(ev -> ev.event() == null && "你".equals(ev.data()))
                .expectNextMatches(ev -> ev.event() == null && "好".equals(ev.data()))
                .expectNextMatches(ev -> "[DONE]".equals(ev.data()))
                .verifyComplete();

        verify(conversationService).ensureConversation("sess-1", "admin", "问题");
        verify(conversationService).saveUserMessage("sess-1", "问题");
        verify(conversationService).saveAssistantMessage("sess-1", "你好");
    }

    @Test
    @DisplayName("askStreamWithCitations：无命中时 citations 事件数据为 []")
    void askStreamWithCitations_emptyCitations() {
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeRetrievalService.toHits(anyList(), anyString(), anyInt())).thenReturn(List.of());
        mockStreamChain(Flux.just("回答"));

        Flux<ServerSentEvent<String>> flux = service.askStreamWithCitations("sess-anon", "问题");

        StepVerifier.create(flux)
                .expectNextMatches(ev -> "citations".equals(ev.event()) && "[]".equals(ev.data()))
                .expectNextMatches(ev -> "回答".equals(ev.data()))
                .expectNextMatches(ev -> "[DONE]".equals(ev.data()))
                .verifyComplete();
    }
}
