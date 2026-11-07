package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.chat.entity.MessageEntity;
import com.example.opsaiagent.chat.service.ConversationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationChatMemoryStoreTest {

    @Test
    void coldStartLoadsOwnedHistoryAndLimitsWindowToTwentyMessages() {
        ConversationService conversationService = mock(ConversationService.class);
        List<MessageEntity> history = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            history.add(message("USER", "消息" + i));
        }
        when(conversationService.loadRecentMessages("session-1", "alice", 20)).thenReturn(history.subList(4, 24));
        ConversationChatMemoryStore store = new ConversationChatMemoryStore(conversationService, 20);
        MessageWindowChatMemory memory = memory("alice", "session-1", store);

        List<ChatMessage> messages = memory.messages();

        assertThat(messages).hasSize(20);
        assertThat(messages.getFirst()).isEqualTo(new UserMessage("消息5"));
        assertThat(messages.getLast()).isEqualTo(new UserMessage("消息24"));
        verify(conversationService).loadRecentMessages("session-1", "alice", 20);
    }

    @Test
    void separatesUsersAndSessionsAndClearDoesNotReloadOrAffectOtherMemory() {
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.loadRecentMessages("same-session", "alice", 20))
                .thenReturn(List.of(message("USER", "alice历史")));
        when(conversationService.loadRecentMessages("same-session", "bob", 20))
                .thenReturn(List.of(message("USER", "bob历史")));
        ConversationChatMemoryStore store = new ConversationChatMemoryStore(conversationService, 20);
        MessageWindowChatMemory alice = memory("alice", "same-session", store);
        MessageWindowChatMemory bob = memory("bob", "same-session", store);

        assertThat(alice.messages()).containsExactly(new UserMessage("alice历史"));
        assertThat(bob.messages()).containsExactly(new UserMessage("bob历史"));
        alice.clear();

        assertThat(alice.messages()).isEmpty();
        assertThat(bob.messages()).containsExactly(new UserMessage("bob历史"));
        verify(conversationService).loadRecentMessages("same-session", "alice", 20);
        verify(conversationService).loadRecentMessages("same-session", "bob", 20);
    }

    @Test
    void memoryIdRoundTripsValuesContainingSeparators() {
        String id = LangChain4jMemoryId.of("alice@example.com", "session.part/一");

        assertThat(LangChain4jMemoryId.parse(id).username()).isEqualTo("alice@example.com");
        assertThat(LangChain4jMemoryId.parse(id).sessionId()).isEqualTo("session.part/一");
    }

    private MessageWindowChatMemory memory(String username, String sessionId, ConversationChatMemoryStore store) {
        return MessageWindowChatMemory.builder()
                .id(LangChain4jMemoryId.of(username, sessionId))
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
}
