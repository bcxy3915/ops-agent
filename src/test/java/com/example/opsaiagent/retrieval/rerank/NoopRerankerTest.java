package com.example.opsaiagent.retrieval.rerank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NoopReranker 单元测试
 * 覆盖：不重排直接截断 / 空文档
 */
@DisplayName("空 Reranker")
class NoopRerankerTest {

    private final NoopReranker reranker = new NoopReranker();

    private Document doc(String text) {
        return new Document(text, Map.of("source", "test.md"));
    }

    @Test
    @DisplayName("rerank：按 topN 截断")
    void rerank_truncates() {
        List<Document> result = reranker.rerank("q",
                List.of(doc("a"), doc("b"), doc("c")), 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("a");
        assertThat(result.get(1).getText()).isEqualTo("b");
    }

    @Test
    @DisplayName("rerank：topN 大于文档数时返回全部")
    void rerank_topNLargerThanInput() {
        List<Document> result = reranker.rerank("q", List.of(doc("a")), 5);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("rerank：空文档返回空")
    void rerank_empty() {
        assertThat(reranker.rerank("q", List.of(), 5)).isEmpty();
    }
}
