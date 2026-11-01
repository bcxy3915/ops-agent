package com.example.opsaiagent.retrieval;

import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.retrieval.diversity.RetrievalDiversifier;
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

    @Mock
    private RetrievalDiversifier diversifier;

    @InjectMocks
    private HybridRetriever hybridRetriever;

    /** diversify 直通桩：保留前 limit 个，便于断言后续编排行为 */
    private void diversifierPassThrough() {
        when(diversifier.diversify(any(), anyInt()))
                .thenAnswer(inv -> {
                    List<Document> list = inv.getArgument(0);
                    int limit = inv.getArgument(1);
                    return list.stream().limit(limit).toList();
                });
    }

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
        diversifierPassThrough();

        List<Document> result = hybridRetriever.retrieve("查询", 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("向量文档");
        verify(diversifier).diversify(List.of(v, b), 20);
        verify(rerankerClient).rerank("查询", List.of(v, b), 3);
    }

    @Test
    @DisplayName("retrieve：融合结果先过同文档治理，rerank 接收治理后的候选")
    void retrieve_diversifyAppliedBeforeRerank() {
        Document v = doc("向量文档");
        Document b = doc("BM25文档");
        Document kept = doc("治理后保留");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(v));
        when(keywordRetriever.search(eq("查询"), eq(20), any(RetrievalScope.class)))
                .thenReturn(List.of(b));
        when(rrfFuser.fuseAndLimit(20, List.of(v), List.of(b))).thenReturn(List.of(v, b));
        when(diversifier.diversify(List.of(v, b), 20)).thenReturn(List.of(kept));
        when(rerankerClient.rerank("查询", List.of(kept), 3)).thenReturn(List.of(kept));

        List<Document> result = hybridRetriever.retrieve("查询", 3);

        assertThat(result).containsExactly(kept);
        verify(rerankerClient).rerank("查询", List.of(kept), 3);
    }

    @Test
    @DisplayName("retrieve：向量检索抛异常时降级仅用 BM25（同样过同文档治理）")
    void retrieve_vectorFailureFallsBackToBm25() {
        Document b = doc("BM25文档");
        List<Document> bm25Results = List.of(b, doc("BM25文档2"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("network error"));
        when(keywordRetriever.search(eq("查询"), eq(20), any(RetrievalScope.class)))
                .thenReturn(bm25Results);
        diversifierPassThrough();

        List<Document> result = hybridRetriever.retrieve("查询", 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("BM25文档");
        verify(diversifier).diversify(bm25Results, 1);
        verify(rrfFuser, never()).fuseAndLimit(anyInt(), any(), any());
        verify(rerankerClient, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("retrieve：向量结果为空时直接返回 BM25 前 topK")
    void retrieve_vectorEmptyUsesBm25TopK() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(keywordRetriever.search(eq("查询"), eq(20), any(RetrievalScope.class)))
                .thenReturn(List.of(doc("a"), doc("b"), doc("c"), doc("d")));
        diversifierPassThrough();

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
