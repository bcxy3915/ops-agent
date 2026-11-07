package com.example.opsaiagent.ai.api;

import java.util.List;

/** 知识检索边界，不允许框架 Document 类型穿过此接口。 */
public interface KnowledgeSearch {
    /**
     * 按查询条件检索知识片段。
     *
     * @param query 检索文本
     * @param limit 返回数量上限
     * @param context 租户及权限过滤上下文
     * @return 按相关度排序的知识命中项
     */
    List<KnowledgeHit> search(String query, int limit, SearchContext context);
}
