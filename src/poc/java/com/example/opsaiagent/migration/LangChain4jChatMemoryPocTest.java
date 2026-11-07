package com.example.opsaiagent.migration;

import com.example.opsaiagent.chat.entity.MessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jChatMemoryPocTest {

    @Test
    void mapsDatabaseHistoryRolesAndIgnoresBlankContent() {
        MessageEntity user = message("USER", "检查 todo-service");
        MessageEntity assistant = message("ASSISTANT", "服务当前健康");
        MessageEntity blank = message("ASSISTANT", "  ");

        List<ChatMessage> messages = LangChain4jMemoryHistoryMapper
                .toChatMessages(List.of(user, assistant, blank));

        assertThat(messages).containsExactly(
                new UserMessage("检查 todo-service"),
                new AiMessage("服务当前健康"));
    }

    @Test
    void keepsOnlyTwentyMessagesInTheWindow() {
        MessageWindowChatMemory memory = memory("session-1", new IsolatedStore());
        for (int i = 1; i <= 24; i++) {
            memory.add(new UserMessage("消息" + i));
        }

        assertThat(memory.messages()).hasSize(20);
        assertThat(memory.messages().getFirst()).isEqualTo(new UserMessage("消息5"));
        assertThat(memory.messages().getLast()).isEqualTo(new UserMessage("消息24"));
    }

    @Test
    void isolatesSessionsAndRestoresOnlyTheSameSessionAfterRecreation() {
        IsolatedStore store = new IsolatedStore();
        MessageWindowChatMemory firstSession = memory("session-1", store);
        MessageWindowChatMemory secondSession = memory("session-2", store);
        firstSession.add(new UserMessage("会话一内容"));
        secondSession.add(new UserMessage("会话二内容"));

        MessageWindowChatMemory recreatedSession = memory("session-1", store);

        assertThat(recreatedSession.messages()).containsExactly(new UserMessage("会话一内容"));
        assertThat(secondSession.messages()).containsExactly(new UserMessage("会话二内容"));

        recreatedSession.clear();

        assertThat(recreatedSession.messages()).isEmpty();
        assertThat(secondSession.messages()).containsExactly(new UserMessage("会话二内容"));
    }

    private MessageWindowChatMemory memory(String sessionId, ChatMemoryStore store) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(20)
                .chatMemoryStore(store)
                .build();
    }

    private MessageEntity message(String role, String content) {
        MessageEntity entity = new MessageEntity();
        entity.setRole(role);
        entity.setContent(content);
        return entity;
    }

    private static final class IsolatedStore implements ChatMemoryStore {
        private final Map<Object, List<ChatMessage>> messagesBySession = new HashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return new ArrayList<>(messagesBySession.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            messagesBySession.put(memoryId, new ArrayList<>(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            messagesBySession.remove(memoryId);
        }
    }
}
