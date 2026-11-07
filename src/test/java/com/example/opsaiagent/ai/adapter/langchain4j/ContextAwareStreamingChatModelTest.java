package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.rbac.context.RbacContextSnapshot;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 异步模型回调的权限上下文传播测试。 */
class ContextAwareStreamingChatModelTest {
    /**
     * 清理测试线程的上下文。
     * @return 无返回值
     */
    @AfterEach
    void clear() { RbacContextSnapshot.capture().clear(); }

    /**
     * 验证模型回调使用请求租户且不污染线程原有租户。
     * @return 无返回值
     */
    @Test
    void callbacksRestoreRequestAndPreviousContext() {
        var delegate = mock(StreamingChatModel.class);
        var handler = mock(StreamingChatResponseHandler.class);
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("测试"))).build();
        TenantContext.set("tenant-a");
        new ContextAwareStreamingChatModel(delegate).chat(request, handler);
        var captor = ArgumentCaptor.forClass(StreamingChatResponseHandler.class);
        verify(delegate).chat(eq(request), captor.capture());
        doAnswer(call -> {
            assertThat(TenantContext.get()).isEqualTo("tenant-a");
            return null;
        }).when(handler).onPartialResponse("回答");
        CompletableFuture.runAsync(() -> {
            TenantContext.set("tenant-worker");
            try {
                captor.getValue().onPartialResponse("回答");
                assertThat(TenantContext.get()).isEqualTo("tenant-worker");
            } finally { TenantContext.clear(); }
        }).join();
        assertThat(TenantContext.get()).isEqualTo("tenant-a");
        verify(handler).onPartialResponse("回答");
    }
}