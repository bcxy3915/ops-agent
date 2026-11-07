package com.example.opsaiagent.ai.api;

import java.util.Map;

/** AI 适配器使用的框架无关聊天请求。 */
public record ChatRequest(String sessionId, String question, String systemPrompt,
                          Map<String, Object> attributes) {
    public ChatRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
