package com.example.opsaiagent.retrieval.rerank;

import com.example.opsaiagent.knowledge.model.Document;

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
     * @return 启用精排时为通过相关性阈值的文档，数量不超过 topN，允许为空；禁用时仅截断
     */
    List<Document> rerank(String query, List<Document> documents, int topN);
}
