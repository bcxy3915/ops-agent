package com.example.opsaiagent.ai.api;

import java.util.Map;

public record SearchContext(String tenantId, String departmentId, String username,
                            Map<String, Object> filters) {
    public SearchContext {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
