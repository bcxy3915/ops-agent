package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.ChatStreamEvent;
import com.example.opsaiagent.ai.api.StreamingChatGateway;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.PartialResponse;
import org.reactivestreams.Publisher;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/** LangChain4j 流式聊天适配器，保持项目定义的事件顺序。 */
public class LangChain4jStreamingChatGateway implements StreamingChatGateway {

    private final StreamingChatModel chatModel;

    /**
     * 创建 LangChain4j 流式聊天适配器。
     *
     * @param chatModel LangChain4j 流式聊天模型
     */
    public LangChain4jStreamingChatGateway(StreamingChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 调用 LangChain4j 流式模型并转换为项目事件。
     *
     * @param request 项目聊天请求
     * @return 流式聊天事件发布者
     */
    @Override
    public Publisher<ChatStreamEvent> stream(ChatRequest request) {
        Flux<ChatModelStreamingEvent> events = JdkFlowAdapter
                .flowPublisherToFlux(chatModel.chat(toRequest(request)));
        Flux<ChatStreamEvent> tokens = events
                .ofType(PartialResponse.class)
                .map(PartialResponse::text)
                .filter(text -> text != null && !text.isEmpty())
                .map(ChatStreamEvent::token);
        return tokens.concatWithValues(ChatStreamEvent.done());
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
        return dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages).build();
    }
}
