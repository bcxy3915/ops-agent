package com.example.opsaiagent.service;

import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import com.example.opsaiagent.tools.ServiceDiscoveryTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpsAgentService 单元测试
 * 覆盖：ask / askStream / 会话保存 / 检索空场景 / 异常兜底
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

    private OpsAgentService service;

    @BeforeEach
    void setUp() {
        service = new OpsAgentService(
                chatClient, healthCheckTools, metricQueryTools,
                metricDiscoveryTools, serviceDiscoveryTools,
                hybridRetriever, conversationService);
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

    // ==================== ask ====================

    @Test
    @DisplayName("ask：检索到文档，返回 LLM 回答")
    void ask_withDocuments() {
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("这是知识库内容")));
        // deep stubs 让 chatClient.prompt()...call().content() 都可用
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .call().content()).thenReturn("模拟回答");

        String result = service.ask("sess-1", "todo-service 健康吗");

        assertThat(result).isEqualTo("模拟回答");
    }

    @Test
    @DisplayName("ask：知识库为空，构造空上下文提示")
    void ask_emptyDocuments() {
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .call().content()).thenReturn("回答");

        String result = service.ask("sess-1", "随便问");

        assertThat(result).isEqualTo("回答");
    }

    // ==================== askStream ====================

    @Test
    @DisplayName("askStream：登录用户，保存会话与消息")
    void askStream_authenticated() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("内容")));
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .stream().content()).thenReturn(Flux.just("你", "好"));

        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
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
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .stream().content()).thenReturn(Flux.just("回答"));

        Flux<String> flux = service.askStream("sess-anon", "问题");

        StepVerifier.create(flux)
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
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .stream().content()).thenReturn(Flux.just("A"));

        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
                .expectNext("A", "[DONE]")
                .verifyComplete();
    }

    @Test
    @DisplayName("askStream：LLM 流异常，doFinally 仍触发但不保存空答案")
    void askStream_streamError() {
        mockAuthenticated();
        when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(java.util.function.Consumer.class))
                .tools(any(), any(), any(), any())
                .stream().content()).thenReturn(Flux.error(new RuntimeException("LLM error")));

        Flux<String> flux = service.askStream("sess-1", "问题");

        StepVerifier.create(flux)
                .expectError()
                .verify();

        // 空答案，不应保存
        verify(conversationService, never()).saveAssistantMessage(anyString(), anyString());
    }
}