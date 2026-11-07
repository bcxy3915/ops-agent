package com.example.opsaiagent.ai.api;

import java.util.Map;

public record ToolCall(String name, Map<String, Object> arguments) {
    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
