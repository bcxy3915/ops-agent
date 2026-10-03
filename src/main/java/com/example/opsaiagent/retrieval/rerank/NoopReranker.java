package com.example.opsaiagent.retrieval.rerank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 空 Reranker：不重排，直接截断
 * 用于未启用 Reranker 的场景
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "ops-agent.retrieval.rerank.enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoopReranker implements RerankerClient {

    /**
     * 对候选文档重排序
     * @param query    查询文本
     * @param documents 候选文档
     * @param topN     返回数量
     * @return 按相关性排序的文档
     */
    @Override
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        log.debug("NoopReranker: 跳过重排，直接取前 {} 个", topN);
        return documents.stream().limit(topN).toList();
    }
}
