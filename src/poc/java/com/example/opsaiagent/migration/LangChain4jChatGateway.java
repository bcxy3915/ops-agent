package com.example.opsaiagent.migration;

import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.ChatResponse;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest.Builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** LangChain4j 同步聊天适配器，仅用于迁移 PoC。 */
public final class LangChain4jChatGateway implements ChatGateway {

    private final ChatModel chatModel;

    /**
     * 创建 LangChain4j 同步聊天适配器。
     *
     * @param chatModel LangChain4j 聊天模型
     */
    public LangChain4jChatGateway(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 调用 LangChain4j 聊天模型并转换为项目响应。
     *
     * @param request 项目聊天请求
     * @return 项目聊天响应
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        var response = chatModel.chat(toRequest(request));
        String answer = response.aiMessage() == null ? "" : response.aiMessage().text();
        return new ChatResponse(answer, Map.of(), "langchain4j");
    }

    /**
     * 将项目请求转换为 LangChain4j 请求。
     *
     * @param request 项目聊天请求
     * @return LangChain4j 聊天请求
     */
    private dev.langchain4j.model.chat.request.ChatRequest toRequest(ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(SystemMessage.from(request.systemPrompt()));
        }
        messages.add(UserMessage.from(request.question()));
        Builder builder = dev.langchain4j.model.chat.request.ChatRequest.builder();
        return builder.messages(messages).build();
    }
}
