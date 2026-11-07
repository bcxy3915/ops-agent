package com.example.opsaiagent.ai.api;

import java.util.Map;

public record KnowledgeHit(String id, String text, double score, Map<String, Object> metadata) {
    public KnowledgeHit {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
