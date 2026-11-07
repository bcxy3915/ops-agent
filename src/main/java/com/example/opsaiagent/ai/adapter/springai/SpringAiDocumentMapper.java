package com.example.opsaiagent.ai.adapter.springai;

import com.example.opsaiagent.knowledge.model.Document;
import java.util.List;

/** 仅在尚未替换的 Reader、切分器和回退存储边界转换框架文档。 */
public final class SpringAiDocumentMapper {
    private SpringAiDocumentMapper() { }

    /**
     * 将框架文档转为项目文档。
     * @param document 框架文档
     * @return 项目文档
     */
    public static Document fromSpring(org.springframework.ai.document.Document document) {
        return new Document(document.getId(), document.getText(), document.getMetadata(), document.getScore());
    }

    /**
     * 将项目文档转为框架文档。
     * @param document 项目文档
     * @return 框架文档
     */
    public static org.springframework.ai.document.Document toSpring(Document document) {
        return org.springframework.ai.document.Document.builder().id(document.getId()).text(document.getText())
                .metadata(document.getMetadata()).score(document.getScore()).build();
    }

    /**
     * 转换框架文档列表。
     * @param documents 框架文档列表
     * @return 项目文档列表
     */
    public static List<Document> fromSpring(List<org.springframework.ai.document.Document> documents) {
        return documents.stream().map(SpringAiDocumentMapper::fromSpring).toList();
    }

    /**
     * 转换项目文档列表。
     * @param documents 项目文档列表
     * @return 框架文档列表
     */
    public static List<org.springframework.ai.document.Document> toSpring(List<Document> documents) {
        return documents.stream().map(SpringAiDocumentMapper::toSpring).toList();
    }
}
