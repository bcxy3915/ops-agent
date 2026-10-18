package com.example.opsaiagent.controller;

import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.loader.DocumentLoader;
import com.example.opsaiagent.retrieval.KeywordRetriever;
import com.example.opsaiagent.service.OpsAgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ChatController 单元测试
 * 纯 Mockito，不启 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("智能问答接口")
class ChatControllerTest {

    @Mock
    private OpsAgentService opsAgentService;

    @Mock
    private KeywordRetriever keywordRetriever;

    @Mock
    private DocumentLoader documentLoader;

    @InjectMocks
    private ChatController controller;

    // ==================== reloadDocument ====================

    @Test
    @DisplayName("重载知识库：调用 DocumentLoader 并返回 success 状态")
    void reloadDocument() {
        when(documentLoader.reload()).thenReturn(Map.of(
                "status", "success", "loaded", 3, "targets", 3, "message", "文档加载完成"));

        Map<String, Object> result = controller.reloadDocument();

        assertThat(result).containsEntry("status", "success");
        assertThat(result).containsEntry("loaded", 3);
    }

    // ==================== ask ====================

    @Test
    @DisplayName("同步问答：返回答案与 citations")
    void ask() {
        AskResponse resp = AskResponse.builder()
                .sessionId("sess-1")
                .question("问题")
                .answer("答案")
                .citations(List.of(RetrievalHitResponse.builder()
                        .docId(1L).title("运维手册").layer("BUILTIN")
                        .trustStatus("VERIFIED").version(1).score(0.9)
                        .build()))
                .build();
        when(opsAgentService.askWithCitations("sess-1", "问题")).thenReturn(resp);

        AskResponse result = controller.ask("问题", "sess-1");

        assertThat(result.getSessionId()).isEqualTo("sess-1");
        assertThat(result.getQuestion()).isEqualTo("问题");
        assertThat(result.getAnswer()).isEqualTo("答案");
        assertThat(result.getCitations()).hasSize(1);
        assertThat(result.getCitations().get(0).getTitle()).isEqualTo("运维手册");
    }

    // ==================== bm25Search ====================

    @Test
    @DisplayName("BM25 检索：返回结果列表")
    void bm25Search() {
        Document doc1 = new Document("文档1内容", Map.of("source", "test1.md"));
        Document doc2 = new Document("文档2内容", Map.of("source", "test2.md"));
        when(keywordRetriever.search("查询", 5)).thenReturn(List.of(doc1, doc2));

        Map<String, Object> result = controller.bm25Search("查询", 5);

        assertThat(result).containsEntry("query", "查询");
        assertThat(result).containsEntry("count", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).containsEntry("source", "test1.md");
    }

    @Test
    @DisplayName("BM25 检索：空结果")
    void bm25Search_empty() {
        when(keywordRetriever.search("无匹配", 5)).thenReturn(List.of());

        Map<String, Object> result = controller.bm25Search("无匹配", 5);

        assertThat(result).containsEntry("count", 0);
    }

    // ==================== askStream ====================

    @Test
    @DisplayName("流式问答：citations 事件 + token + [DONE]")
    void askStream() {
        when(opsAgentService.askStreamWithCitations("sess-1", "问题"))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("").event("citations").data("[]").build(),
                        ServerSentEvent.builder("你").build(),
                        ServerSentEvent.builder("好").build(),
                        ServerSentEvent.builder("[DONE]").build()));

        Flux<ServerSentEvent<String>> flux = controller.askStream("问题", "sess-1");

        StepVerifier.create(flux)
                .expectNextMatches(ev -> "citations".equals(ev.event()) && "[]".equals(ev.data()))
                .expectNextMatches(ev -> "你".equals(ev.data()))
                .expectNextMatches(ev -> "好".equals(ev.data()))
                .expectNextMatches(ev -> "[DONE]".equals(ev.data()))
                .verifyComplete();
    }
}
