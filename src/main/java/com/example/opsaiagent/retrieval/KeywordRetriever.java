package com.example.opsaiagent.retrieval;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 关键词检索引擎接口
 * 抽象设计：未来可替换实现
 * - InMemoryBm25Retriever（当前）
 * - PostgresBm25Retriever（中等规模）
 * - ElasticsearchRetriever（大规模）
 */
public interface KeywordRetriever {

    /**
     * 关键词检索
     * @param query 查询文本，会做分词处理；纯符号（如 "::"）可能匹配不到结果
     * @param topK  返回条数，建议 3~10；超过 20 会明显拖慢响应（大模型 prompt 变长）
     * @return 按相关度降序排列；检索不到时返回空 List，不会返回 null
     */
    List<Document> search(String query, int topK);

    /**
     * 重建索引
     */
    void rebuild();
}
