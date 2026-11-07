package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.rbac.context.RbacContextSnapshot;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.*;

/** 在模型异步回调中恢复权限上下文，覆盖工具执行与下一轮模型请求。 */
public final class ContextAwareStreamingChatModel implements StreamingChatModel {
    private final StreamingChatModel delegate;

    /**
     * 创建带请求上下文传播的流式模型。
     * @param delegate 实际流式模型
     * @return 无返回值
     */
    public ContextAwareStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    /**
     * 保留模型默认请求参数。
     * @return 模型默认请求参数
     */
    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    /**
     * 保留实际模型的能力声明。
     * @return 模型支持的能力集合
     */
    @Override
    public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    /**
     * 保留实际模型的供应商标识。
     * @return 模型供应商
     */
    @Override
    public dev.langchain4j.model.ModelProvider provider() {
        return delegate.provider();
    }

    /**
     * 发起请求，并为全部回调绑定当前请求上下文。
     * @param request 模型请求
     * @param handler 框架回调处理器
     * @return 无返回值
     */
    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        delegate.chat(request, new StreamingChatResponseHandler() {
            /**
             * 在请求权限上下文中转发模型事件。
             * @param response 回调参数
             * @return 无返回值
             */
            @Override
            public void onPartialResponse(String response) {
                forward(snapshot, () -> handler.onPartialResponse(response));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param response 回调参数
             * @param context 回调参数
             * @return 无返回值
             */
            @Override
            public void onPartialResponse(PartialResponse response, PartialResponseContext context) {
                forward(snapshot, () -> handler.onPartialResponse(response, context));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param thinking 回调参数
             * @return 无返回值
             */
            @Override
            public void onPartialThinking(PartialThinking thinking) {
                forward(snapshot, () -> handler.onPartialThinking(thinking));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param thinking 回调参数
             * @param context 回调参数
             * @return 无返回值
             */
            @Override
            public void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
                forward(snapshot, () -> handler.onPartialThinking(thinking, context));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param toolCall 回调参数
             * @return 无返回值
             */
            @Override
            public void onPartialToolCall(PartialToolCall toolCall) {
                forward(snapshot, () -> handler.onPartialToolCall(toolCall));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param toolCall 回调参数
             * @param context 回调参数
             * @return 无返回值
             */
            @Override
            public void onPartialToolCall(PartialToolCall toolCall, PartialToolCallContext context) {
                forward(snapshot, () -> handler.onPartialToolCall(toolCall, context));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param toolCall 回调参数
             * @return 无返回值
             */
            @Override
            public void onCompleteToolCall(CompleteToolCall toolCall) {
                forward(snapshot, () -> handler.onCompleteToolCall(toolCall));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param response 回调参数
             * @return 无返回值
             */
            @Override
            public void onCompleteResponse(ChatResponse response) {
                forward(snapshot, () -> handler.onCompleteResponse(response));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param event 回调参数
             * @return 无返回值
             */
            @Override
            public void onUnmappedRawEvent(Object event) {
                forward(snapshot, () -> handler.onUnmappedRawEvent(event));
            }

            /**
             * 在请求权限上下文中转发模型事件。
             * @param error 回调参数
             * @return 无返回值
             */
            @Override
            public void onError(Throwable error) {
                forward(snapshot, () -> handler.onError(error));
            }
        });
    }

    /**
     * 执行回调后恢复线程原有上下文，避免嵌套调用与线程复用污染。
     * @param snapshot 请求上下文
     * @param callback 模型回调
     * @return 无返回值
     */
    private static void forward(RbacContextSnapshot snapshot, Runnable callback) {
        RbacContextSnapshot previous = RbacContextSnapshot.capture();
        snapshot.restore();
        try {
            callback.run();
        } finally {
            previous.restore();
        }
    }
}
