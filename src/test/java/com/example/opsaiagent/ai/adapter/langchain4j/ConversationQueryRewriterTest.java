package com.example.opsaiagent.ai.adapter.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 使用框架真实改写器与可控模型验证指代消解，不访问外部模型。 */
class ConversationQueryRewriterTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ConversationQueryRewriter rewriter = new ConversationQueryRewriter(model);

    /**
     * 追问结合历史生成独立检索问题。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void resolvesFollowUpUsingRecentHistory() {
        when(model.chat(anyString())).thenReturn("商城数据库连接池满了，应该如何调整配置？");
        String result = rewriter.rewrite("那应该怎么调整？", history());
        assertThat(result).isEqualTo("商城数据库连接池满了，应该如何调整配置？");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(prompt.capture());
        assertThat(prompt.getValue()).contains("商城数据库连接池满了", "那应该怎么调整？");
    }

    /**
     * 无历史或明确新主题不额外调用模型。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void skipsFirstQuestionAndExplicitNewTopic() {
        assertThat(rewriter.rewrite("那应该怎么调整？", List.of())).isEqualTo("那应该怎么调整？");
        assertThat(rewriter.rewrite("Redis 缓存雪崩怎么处理？", history())).isEqualTo("Redis 缓存雪崩怎么处理？");
        verifyNoInteractions(model);
    }

    /**
     * 改写失败、空白或多行解释均保守返回原问题。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void fallsBackOnFailureOrInvalidOutput() {
        when(model.chat(anyString())).thenThrow(new IllegalStateException("模型不可用"));
        assertThat(rewriter.rewrite("那怎么解决？", history())).isEqualTo("那怎么解决？");
        reset(model);
        when(model.chat(anyString())).thenReturn("", "解释\n问题");
        assertThat(rewriter.rewrite("那怎么解决？", history())).isEqualTo("那怎么解决？");
        assertThat(rewriter.rewrite("那怎么解决？", history())).isEqualTo("那怎么解决？");
    }

    /**
     * 只使用最近四条消息且不修改原历史。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void limitsHistoryWithoutMutatingIt() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("早期无关主题"));
        messages.addAll(history());
        messages.add(UserMessage.from("连接数为20"));
        messages.add(AiMessage.from("检查等待连接数"));
        when(model.chat(anyString())).thenReturn("商城连接池20个连接占满怎么调整？");
        rewriter.rewrite("那怎么调整？", messages);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(prompt.capture());
        assertThat(prompt.getValue()).doesNotContain("早期无关主题").contains("连接数为20");
        assertThat(messages).hasSize(5);
    }

    /**
     * 过长历史不发出额外请求。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void skipsOversizedHistory() {
        assertThat(rewriter.rewrite("那怎么调整？", List.of(UserMessage.from("长".repeat(2001)))))
                .isEqualTo("那怎么调整？");
        verifyNoInteractions(model);
    }

    /**
     * 创建当前会话的两条历史消息。
     * 无入参。
     * @return 测试历史
     */
    private List<ChatMessage> history() {
        return List.of(UserMessage.from("商城数据库连接池满了"), AiMessage.from("需要检查连接占用和连接池配置"));
    }
}
