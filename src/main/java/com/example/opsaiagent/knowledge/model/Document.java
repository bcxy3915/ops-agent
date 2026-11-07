package com.example.opsaiagent.knowledge.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 项目知识文档：正文、来源元数据与评分，不依赖 AI 框架。 */
@Getter
@EqualsAndHashCode
public final class Document {
    private final String id;
    private final String text;
    private final Map<String, Object> metadata;
    private final Double score;

    /**
     * 创建仅含正文的文档。
     * @param text 正文
     * @return 无返回值
     */
    public Document(String text) { this(text, Map.of()); }

    /**
     * 创建正文和元数据文档。
     * @param text 正文
     * @param metadata 元数据
     * @return 无返回值
     */
    public Document(String text, Map<String, Object> metadata) {
        this(UUID.randomUUID().toString(), text, metadata);
    }

    /**
     * 创建保留现有 ID 的文档。
     * @param id 文档 ID
     * @param text 正文
     * @param metadata 元数据
     * @return 无返回值
     */
    public Document(String id, String text, Map<String, Object> metadata) {
        this(id, text, metadata, null);
    }

    /**
     * 创建包含检索评分的文档，元数据保留可变语义以支持融合和重排。
     * @param id 文档 ID
     * @param text 正文
     * @param metadata 元数据
     * @param score 检索评分，可为空
     * @return 无返回值
     */
    public Document(String id, String text, Map<String, Object> metadata, Double score) {
        this.id = java.util.Objects.requireNonNull(id);
        this.text = text;
        this.metadata = new HashMap<>(metadata == null ? Map.of() : metadata);
        this.score = score;
    }
}
