package com.example.opsaiagent.ai.api;

import org.reactivestreams.Publisher;

/** 流式模型调用边界，事件顺序属于应用契约。 */
public interface StreamingChatGateway {
    /**
     * 以流的形式调用聊天模型。
     *
     * @param request 聊天请求
     * @return 流式聊天事件发布者
     */
    Publisher<ChatStreamEvent> stream(ChatRequest request);
}
