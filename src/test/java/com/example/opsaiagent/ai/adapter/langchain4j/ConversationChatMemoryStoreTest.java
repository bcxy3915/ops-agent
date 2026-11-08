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

    /**
     * 验证同名用户和同会话 ID 在两个租户下拥有独立缓存，错误上下文不能读缓存。
     * @return 无返回值
     */
    @Test
    void isolatesSameUserAndSessionAcrossTenantsIncludingCacheHits() {
        var store = new ConversationChatMemoryStore(mock(ConversationService.class), 20);
        try {
            com.example.opsaiagent.rbac.context.TenantContext.set("tenant-a");
            String tenantAId = LangChain4jMemoryId.of("alice", "same-session");
            store.updateMessages(tenantAId, List.of(new UserMessage("租户甲历史")));
            com.example.opsaiagent.rbac.context.TenantContext.set("tenant-b");
            String tenantBId = LangChain4jMemoryId.of("alice", "same-session");
            store.updateMessages(tenantBId, List.of(new UserMessage("租户乙历史")));

            assertThat(tenantBId).isNotEqualTo(tenantAId);
            assertThat(store.getMessages(tenantBId)).containsExactly(new UserMessage("租户乙历史"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.getMessages(tenantAId))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            com.example.opsaiagent.rbac.context.TenantContext.set("tenant-a");
            assertThat(store.getMessages(tenantAId)).containsExactly(new UserMessage("租户甲历史"));
        } finally {
            com.example.opsaiagent.rbac.context.TenantContext.clear();
        }
    }

    /**
     * 验证新建仓库模拟进程重启时从持久层恢复，而非依赖旧仓库缓存。
     * @return 无返回值
     */
    @Test
    void newStoreAfterRestartRestoresPersistedUserAndAssistantMessages() {
        ConversationService conversationService = mock(ConversationService.class);
        String id = LangChain4jMemoryId.of("alice", "session-1");
        when(conversationService.loadRecentMessages("session-1", "alice", 20))
                .thenReturn(List.of(message("USER", "检查订单服务"), message("ASSISTANT", "服务健康")));
        var beforeRestart = new ConversationChatMemoryStore(conversationService, 20);
        beforeRestart.updateMessages(id, List.of(new UserMessage("仅在旧缓存中的内容")));

        var afterRestart = new ConversationChatMemoryStore(conversationService, 20);
        assertThat(afterRestart.getMessages(id)).containsExactly(new UserMessage("检查订单服务"),
                dev.langchain4j.data.message.AiMessage.from("服务健康"));
        verify(conversationService).loadRecentMessages("session-1", "alice", 20);
    }

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
