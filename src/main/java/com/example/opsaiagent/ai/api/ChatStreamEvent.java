package com.example.opsaiagent.ai.api;

import java.util.Map;

/** 有序流式事件；实现必须保持 token、citations、done 的顺序。 */
public record ChatStreamEvent(Type type, String data, Map<String, Object> attributes) {
    public enum Type { TOKEN, CITATIONS, DONE, ERROR }

    public ChatStreamEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * 创建文本片段事件。
     *
     * @param value 文本片段
     * @return 文本片段事件
     */
    public static ChatStreamEvent token(String value) {
        return new ChatStreamEvent(Type.TOKEN, value, Map.of());
    }

    /**
     * 创建流结束事件。
     *
     * @return 流结束事件
     */
    public static ChatStreamEvent done() {
        return new ChatStreamEvent(Type.DONE, null, Map.of());
    }
}
