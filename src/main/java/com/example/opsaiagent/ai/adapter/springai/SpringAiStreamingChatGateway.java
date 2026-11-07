package com.example.opsaiagent.ai.adapter.springai;

import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.ChatStreamEvent;
import com.example.opsaiagent.ai.api.StreamingChatGateway;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/** Spring AI 流式聊天适配器，保持项目定义的事件顺序。 */
@RequiredArgsConstructor
public class SpringAiStreamingChatGateway implements StreamingChatGateway {

    private final ChatClient chatClient;

    /**
     * 调用 Spring AI 流式模型并输出文本片段及结束事件。
     *
     * @param request 项目聊天请求
     * @return 流式聊天事件发布者
     */
    @Override
    public Publisher<ChatStreamEvent> stream(ChatRequest request) {
        Flux<ChatStreamEvent> tokens = chatClient.prompt()
                .system(request.systemPrompt())
                .user(request.question())
                .stream()
                .content()
                .map(ChatStreamEvent::token);
        return Flux.concat(tokens, Flux.just(ChatStreamEvent.done()));
    }
}
