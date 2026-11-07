package com.example.opsaiagent.ai.adapter.springai;

import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import org.springframework.ai.chat.memory.ChatMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;

/** Spring AI 结构化输出适配器，供迁移期间过渡使用。 */
@RequiredArgsConstructor
public class SpringAiStructuredOutputGateway implements StructuredOutputGateway {

    private final ChatClient chatClient;

    /**
     * 调用 Spring AI 并将结果映射为指定业务类型。
     *
     * @param request 项目聊天请求
     * @param outputType 目标业务类型
     * @param <T> 目标业务类型
     * @return 结构化业务结果
     */
    @Override
    public <T> T generate(ChatRequest request, Class<T> outputType) {
        T result = chatClient.prompt()
                .system(request.systemPrompt())
                .user(request.question())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, request.sessionId()))
                .call()
                .entity(outputType);
        if (result == null) {
            throw new IllegalStateException("模型未返回结构化结果");
        }
        return result;
    }
}
