package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.retrieval.HybridRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.example.opsaiagent.knowledge.model.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将现有混合检索结果适配为 LangChain4j RAG 内容。 */
public class LangChain4jKnowledgeContentRetriever implements ContentRetriever {

    private final HybridRetriever hybridRetriever;
    private final int topK;

    /**
     * 创建 LangChain4j 知识内容检索器。
     *
     * @param hybridRetriever 现有混合检索服务
     * @param topK 每次返回的知识片段数量
     * @return 无返回值
     */
    public LangChain4jKnowledgeContentRetriever(HybridRetriever hybridRetriever, int topK) {
        this.hybridRetriever = hybridRetriever;
        this.topK = topK;
    }

    /**
     * 检索并转换知识片段。
     *
     * @param query LangChain4j 查询对象
     * @return LangChain4j RAG 内容列表
     */
    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        return toContents(retrieveDocuments(query.text()));
    }

    /**
     * 将混合检索文档转换为 LangChain4j RAG 内容。
     *
     * @param documents 混合检索文档列表
     * @return LangChain4j RAG 内容列表
     */
    public List<Content> toContents(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Content> contents = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document == null || document.getText() == null || document.getText().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = document.getMetadata() == null
                    ? Map.of() : document.getMetadata();
            Metadata segmentMetadata = new Metadata();
            metadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    segmentMetadata.put(key, String.valueOf(value));
                }
            });
            TextSegment segment = TextSegment.from(document.getText(), segmentMetadata);
            Object score = metadata.getOrDefault("score", document.getScore());
            Content content = score == null
                    ? Content.from(segment)
                    : Content.from(segment, Map.of(ContentMetadata.SCORE, score));
            contents.add(content);
        }
        return contents;
    }

    /**
     * 查询现有混合检索流水线，供引用和命中记录复用。
     *
     * @param query 检索文本
     * @return 项目文档列表
     */
    public List<Document> retrieveDocuments(String query) {
        return query == null || query.isBlank() ? List.of() : hybridRetriever.retrieve(query, topK);
    }
}
