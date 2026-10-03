package com.example.opsaiagent.retrieval;

import com.example.opsaiagent.retrieval.fusion.RrfFuser;
import com.example.opsaiagent.retrieval.rerank.RerankerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索编排：
 * 向量 + BM25 → RRF 融合 → Reranker 精排
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetriever {

    private final VectorStore vectorStore;
    private final KeywordRetriever keywordRetriever;
    private final RrfFuser rrfFuser;
    private final RerankerClient rerankerClient;

    /**
     * 混合检索
     * @param query 查询文本
     * @param topK 返回结果数量
     * @return 检索结果
     */
    public List<Document> retrieve(String query, int topK) {
        // 1. 双路召回
        // 向量检索
        List<Document> vectorResults = vectorSearch(query, 20);
        // BM25 检索
        List<Document> bm25Results = keywordRetriever.search(query, 20);
        log.info("混合检索: 向量命中 {} 个, BM25 命中 {} 个", vectorResults.size(), bm25Results.size());

        // 2. RRF 融合
        List<Document> fused = rrfFuser.fuseAndLimit(20, vectorResults, bm25Results);
        log.info("RRF 融合后: {} 个", fused.size());

        // 3. Reranker 精排
        List<Document> reranked = rerankerClient.rerank(query, fused, topK);
        log.info("Reranker 后: {} 个", reranked.size());

        return reranked;
    }

    /**
     * 向量检索
     * @param query 查询文本
     * @param topK 返回结果数量
     * @return 检索结果
     */
    private List<Document> vectorSearch(String query, int topK) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.5)
                            .build()
            );
        } catch (Exception e) {
            log.error("向量检索失败", e);
            return List.of();
        }
    }
}