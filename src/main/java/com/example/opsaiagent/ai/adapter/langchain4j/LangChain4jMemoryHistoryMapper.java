package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.chat.entity.MessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/** 将数据库会话历史转换为 LangChain4j 消息。 */
public final class LangChain4jMemoryHistoryMapper {

    private LangChain4jMemoryHistoryMapper() {
    }

    /**
     * 将数据库消息按原顺序转换为用户和助手消息。
     *
     * @param history 按消息 ID 正序排列的历史记录
     * @return 已过滤空内容的 LangChain4j 消息
     */
    public static List<ChatMessage> toChatMessages(List<MessageEntity> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> "ASSISTANT".equals(message.getRole())
                        ? (ChatMessage) new AiMessage(message.getContent())
                        : new UserMessage(message.getContent()))
                .toList();
    }
}
