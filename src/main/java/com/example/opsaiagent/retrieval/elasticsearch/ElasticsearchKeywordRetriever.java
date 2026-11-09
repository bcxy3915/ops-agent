package com.example.opsaiagent.retrieval.elasticsearch;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jElasticsearchStore;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.retrieval.KeywordRetriever;
import com.example.opsaiagent.retrieval.RetrievalScope;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** ES BM25 检索，使用与向量检索相同的权限边界。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ops-agent.retrieval.keyword.type", havingValue = "elasticsearch", matchIfMissing = true)
public class ElasticsearchKeywordRetriever implements KeywordRetriever {
    private final LangChain4jElasticsearchStore store;
    private final DataScope dataScope;

    /**
     * 在当前用户范围执行关键词检索。
     * @param query 问题
     * @param topK 最大数量
     * @return 检索结果
     */
    @Override
    public List<Document> search(String query, int topK) { return search(query, topK, RetrievalScope.current(dataScope)); }

    /**
     * 在指定可信范围执行关键词检索。
     * @param query 问题
     * @param topK 最大数量
     * @param scope 权限
     * @return 检索结果
     */
    @Override
    public List<Document> search(String query, int topK, RetrievalScope scope) { return store.keywordSearch(query, topK, scope); }

    /**
     * 请求投递已有待同步分块；完整重建由知识库重新索引入口负责。
     * @return 无返回值，无入参
     */
    @Override
    public void rebuild() { }
}
