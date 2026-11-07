package com.example.opsaiagent.migration;

import com.example.opsaiagent.chat.entity.MessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/** 将现有会话历史映射为 LangChain4j 消息，仅供迁移 PoC 使用。 */
public final class LangChain4jMemoryHistoryMapper {

    private LangChain4jMemoryHistoryMapper() {
    }

    /**
     * 将数据库消息按原有顺序转换为 LangChain4j 用户和助手消息。
     *
     * @param history 已按消息 ID 正序排列的历史消息
     * @return 去除空内容后的 LangChain4j 消息列表
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
