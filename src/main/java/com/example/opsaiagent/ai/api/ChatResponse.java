package com.example.opsaiagent.ai.api;

import java.util.Map;

/** 框架无关的非流式聊天响应。 */
public record ChatResponse(String answer, Map<String, Object> usage, String provider) {
    public ChatResponse {
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }
}
