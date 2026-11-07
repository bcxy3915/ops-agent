package com.example.opsaiagent.ai.api;

/** 结构化模型输出边界。 */
public interface StructuredOutputGateway {
    /**
     * 按指定类型生成结构化结果。
     *
     * @param request 聊天请求
     * @param outputType 目标结果类型
     * @param <T> 目标结果类型
     * @return 结构化结果
     */
    <T> T generate(ChatRequest request, Class<T> outputType);
}
