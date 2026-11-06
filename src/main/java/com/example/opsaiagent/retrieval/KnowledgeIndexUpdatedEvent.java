package com.example.opsaiagent.retrieval;

/**
 * 知识索引更新事件：文档向量入库成功后发布，用于刷新关键词索引。
 *
 * @param docId 文档 ID
 */
public record KnowledgeIndexUpdatedEvent(Long docId) {
}
