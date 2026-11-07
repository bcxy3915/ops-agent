package com.example.opsaiagent.ai.adapter.springai;

import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/** Spring AI 同步聊天适配器，供迁移期间过渡使用。 */
@RequiredArgsConstructor
public class SpringAiChatGateway implements ChatGateway {

    private final ChatClient chatClient;

    /**
     * 调用 Spring AI 聊天模型并转换为项目响应。
     *
     * @param request 项目聊天请求
     * @return 项目聊天响应
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        String answer = chatClient.prompt()
                .system(request.systemPrompt())
                .user(request.question())
                .call()
                .content();
        return new ChatResponse(answer, Map.of(), "spring-ai");
    }
}
