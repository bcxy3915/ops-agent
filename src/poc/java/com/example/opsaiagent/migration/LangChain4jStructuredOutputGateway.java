package com.example.opsaiagent.migration;

import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

/** LangChain4j 结构化输出适配器，仅用于迁移 PoC。 */
public final class LangChain4jStructuredOutputGateway implements StructuredOutputGateway {

    private final LangChain4jChatGateway chatGateway;
    private final ObjectMapper objectMapper;

    /**
     * 创建 LangChain4j 结构化输出适配器。
     *
     * @param chatModel LangChain4j 聊天模型
     * @param objectMapper JSON 映射器
     */
    public LangChain4jStructuredOutputGateway(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatGateway = new LangChain4jChatGateway(chatModel);
        this.objectMapper = objectMapper;
    }

    /**
     * 调用模型并将 JSON 响应映射为指定业务类型。
     *
     * @param request 项目聊天请求
     * @param outputType 目标业务类型
     * @param <T> 目标业务类型
     * @return 结构化业务结果
     */
    @Override
    public <T> T generate(ChatRequest request, Class<T> outputType) {
        String content = chatGateway.chat(request).answer();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型未返回结构化结果");
        }
        try {
            return objectMapper.readValue(content, outputType);
        } catch (Exception e) {
            throw new IllegalStateException("模型返回的结构化结果格式非法", e);
        }
    }
}
