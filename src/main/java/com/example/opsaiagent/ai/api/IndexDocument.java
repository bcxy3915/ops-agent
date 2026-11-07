package com.example.opsaiagent.ai.api;

import java.util.Map;

public record IndexDocument(String id, String content, float[] embedding,
                            Map<String, Object> metadata) {
    public IndexDocument {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
