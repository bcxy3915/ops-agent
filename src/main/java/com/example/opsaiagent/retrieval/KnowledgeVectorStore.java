package com.example.opsaiagent.retrieval;

import java.util.Collection;
import com.example.opsaiagent.knowledge.model.Document;
import java.util.List;

/** 知识向量读写边界，统一使用项目文档类型。 */
public interface KnowledgeVectorStore {
    /**
     * 写入已有文档 ID 对应的向量。
     * @param documents 待写文档
     * @return 无返回值
     */
    void write(List<Document> documents);

    /**
     * 在权限范围内执行余弦相似度查询。
     * @param query 查询文本
     * @param limit 最大返回数量
     * @param scope 服务端检索权限范围
     * @return 按相关度排序的文档
     */
    List<Document> search(String query, int limit, RetrievalScope scope);

    /**
     * 在业务事务中登记指定来源的删除任务。
     * @param sources 需要删除的来源标识
     * @return 登记删除的分块数
     */
    int deleteBySources(Collection<String> sources);

    /**
     * 读取持久化投影，不依赖搜索引擎是否在线。
     * @return 当前未删除的投影文档，无入参
     */
    List<Document> documents();
}
