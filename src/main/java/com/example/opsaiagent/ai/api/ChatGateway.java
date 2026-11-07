package com.example.opsaiagent.ai.api;

/** 应用自有的同步模型调用边界。 */
public interface ChatGateway {
    /**
     * 同步调用聊天模型。
     *
     * @param request 聊天请求
     * @return 模型响应
     */
    ChatResponse chat(ChatRequest request);
}
