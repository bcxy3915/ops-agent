package com.example.opsaiagent.ai.api;

import java.util.List;

/** 面向后续 LangChain4j/Pgvector 实现的向量索引边界。 */
public interface VectorIndex {
    /**
     * 新增或更新向量文档。
     *
     * @param documents 待写入的文档列表
     */
    void upsert(List<IndexDocument> documents);

    /**
     * 按来源删除指定范围内的文档。
     *
     * @param source 文档来源
     * @param context 租户及权限过滤上下文
     * @return 删除的文档数量
     */
    int deleteBySource(String source, SearchContext context);
}
