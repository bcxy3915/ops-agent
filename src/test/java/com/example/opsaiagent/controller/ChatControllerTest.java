package com.example.opsaiagent.controller;

import com.example.opsaiagent.retrieval.KeywordRetriever;
import com.example.opsaiagent.service.OpsAgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
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

    @InjectMocks
    private ChatController controller;

    // ==================== loadDocument ====================

    @Test
    @DisplayName("重载知识库：返回 success 状态")
    void loadDocument() {
        Map<String, String> result = controller.loadDocument();

        assertThat(result).containsEntry("status", "success");
    }

    // ==================== ask ====================

    @Test
    @DisplayName("同步问答：返回答案 Map")
    void ask() {
        when(opsAgentService.ask("sess-1", "问题")).thenReturn("答案");

        Map<String, String> result = controller.ask("问题", "sess-1");

        assertThat(result)
                .containsEntry("sessionId", "sess-1")
                .containsEntry("question", "问题")
                .containsEntry("answer", "答案");
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
    @DisplayName("流式问答：输出附加 [DONE] 标记")
    void askStream() {
        when(opsAgentService.askStream("sess-1", "问题"))
                .thenReturn(Flux.just("你", "好"));

        Flux<String> flux = controller.askStream("问题", "sess-1");

        StepVerifier.create(flux)
                .expectNext("你", "好", "[DONE]")
                .verifyComplete();
    }
}