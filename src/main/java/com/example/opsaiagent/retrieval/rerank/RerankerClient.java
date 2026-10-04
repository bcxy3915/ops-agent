package com.example.opsaiagent.retrieval.rerank;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * rerank模块接口
 */
public interface RerankerClient {

    /**
     * 对候选文档重排序
     * @param query    查询文本
     * @param documents 候选文档
     * @param topN     返回数量
     * @return 按相关性排序的文档
     */
    List<Document> rerank(String query, List<Document> documents, int topN);
}
