package com.example.opsaiagent.retrieval;

import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.retrieval.fusion.RrfFuser;
import com.example.opsaiagent.retrieval.rerank.RerankerClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HybridRetriever 单元测试
 * 覆盖：双路召回 + RRF 融合 + Reranker 精排 / 向量检索降级 / BM25 兜底
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("混合检索编排")
class HybridRetrieverTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private KeywordRetriever keywordRetriever;

    @Mock
    private RrfFuser rrfFuser;

    @Mock
    private RerankerClient rerankerClient;

    @Mock
    private DataScope dataScope;

    @InjectMocks
    private HybridRetriever hybridRetriever;

    private Document doc(String text) {
        return new Document(text, Map.of("source", "test.md"));
    }

    @Test
    @DisplayName("retrieve：双路结果 RRF 融合后 Reranker 精排")
    void retrieve_bothPathsFusedAndReranked() {
        Document v = doc("向量文档");
        Document b = doc("BM25文档");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(v));
        when(keywordRetriever.search(eq("查询"), eq(20), any(RetrievalScope.class)))
                .thenReturn(List.of(b));
        when(rrfFuser.fuseAndLimit(20, List.of(v), List.of(b))).thenReturn(List.of(v, b));
        when(rerankerClient.rerank("查询", List.of(v, b), 3)).thenReturn(List.of(v));

        List<Document> result = hybridRetriever.retrieve("查询", 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("向量文档");
        verify(rerankerClient).rerank("查询", List.of(v, b), 3);
    }

    @Test
    @DisplayName("retrieve：向量检索抛异常时降级仅用 BM25")
    void retrieve_vectorFailureFallsBackToBm25() {
        Document b = doc("BM25文档");
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("network error"));
        when(keywordRetriever.search(eq("查询"), eq(20), any(RetrievalScope.class)))
                .thenReturn(List.of(b, doc("BM25文档2")));

        List<Document> result = hybridRetriever.retrieve("查询", 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("BM25文档");
        verify(rrfFuser, never()).fuseAndLimit(anyInt(), any(), any());
        verify(rerankerClient, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("retrieve：向量结果为空时直接返回 BM25 前 topK")
    void retrieve_vectorEmptyUsesBm25TopK() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(keywordRetriever.search(eq("查询"), eq(20), any(RetrievalScope.class)))
                .thenReturn(List.of(doc("a"), doc("b"), doc("c"), doc("d")));

        List<Document> result = hybridRetriever.retrieve("查询", 2);

        assertThat(result).hasSize(2);
        verify(rrfFuser, never()).fuseAndLimit(anyInt(), any(), any());
    }

    @Test
    @DisplayName("retrieve：BM25 无结果时返回空列表")
    void retrieve_bm25Empty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(keywordRetriever.search(eq("查询"), eq(20), any(RetrievalScope.class)))
                .thenReturn(List.of());

        List<Document> result = hybridRetriever.retrieve("查询", 3);

        assertThat(result).isEmpty();
    }
}
