package com.example.opsaiagent.ai.api;

import java.util.Map;

public record ToolExecutionContext(String tenantId, String username, String traceId,
                                   Map<String, Object> attributes) {
    public ToolExecutionContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
