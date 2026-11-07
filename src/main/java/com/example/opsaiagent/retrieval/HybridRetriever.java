package com.example.opsaiagent.retrieval;

import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.retrieval.diversity.RetrievalDiversifier;
import com.example.opsaiagent.retrieval.fusion.RrfFuser;
import com.example.opsaiagent.retrieval.rerank.RerankerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final DataScope dataScope;
    private final RetrievalDiversifier diversifier;
    private KnowledgeVectorStore knowledgeVectorStore;

    /**
     * 注入 LangChain4j 向量存储；缺省时继续使用原回退实现。
     * @param store 知识向量存储
     * @return 无返回值
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKnowledgeVectorStore(KnowledgeVectorStore store) {
        this.knowledgeVectorStore = store;
    }

    /**
     * 混合检索（使用当前请求的数据范围）
     * @param query 查询文本
     * @param topK 返回结果数量
     * @return 检索结果
     */
    public List<Document> retrieve(String query, int topK) {
        return retrieve(query, topK, RetrievalScope.current(dataScope));
    }

    /**
     * 混合检索
     * @param query 查询文本
     * @param topK 返回结果数量
     * @param scope 数据范围
     * @return 检索结果
     */
    public List<Document> retrieve(String query, int topK, RetrievalScope scope) {
        RetrievalScope effective = scope == null ? RetrievalScope.publicOnly() : scope;
        // 1. 双路召回
        // 向量检索
        List<Document> vectorResults = new ArrayList<>();
        try {
            vectorResults = vectorSearch(query, 20, effective);
        } catch (Exception e) {
            // 降级策略：不抛出异常，向量结果为空，后续依赖 BM25
            log.warn("向量检索失败（可能网络问题或 API 限流），降级为仅使用 BM25。错误: {}", e.getMessage());
        }

        // BM25 检索
        List<Document> bm25Results = keywordRetriever.search(query, 20, effective);
        log.info("混合检索: 向量命中 {} 个, BM25 命中 {} 个", vectorResults.size(), bm25Results.size());
        if (vectorResults.isEmpty()) {
            return diversifier.diversify(bm25Results, topK);
        }

        // 2. RRF 融合
        List<Document> fused = rrfFuser.fuseAndLimit(20, vectorResults, bm25Results);
        log.info("RRF 融合后: {} 个", fused.size());

        // 3. 同文档多样性治理（§10.3.10：同文档配额 + 邻近合并，超额块让位给后排文档）
        List<Document> diversified = diversifier.diversify(fused, 20);

        // 4. Reranker 精排
        List<Document> reranked = rerankerClient.rerank(query, diversified, topK);
        log.info("Reranker 后: {} 个", reranked.size());

        return reranked;
    }

    /**
     * 向量检索
     * @param query 查询文本
     * @param topK 返回结果数量
     * @param scope 数据范围
     * @return 检索结果
     */
    private List<Document> vectorSearch(String query, int topK, RetrievalScope scope) {
        try {
            if (knowledgeVectorStore != null) {
                return knowledgeVectorStore.search(query, topK, scope);
            }
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.5)
                            .filterExpression(scope.filterExpression())
                            .build()
            );
        } catch (Exception e) {
            log.error("向量检索失败", e);
            return List.of();
        }
    }
}
