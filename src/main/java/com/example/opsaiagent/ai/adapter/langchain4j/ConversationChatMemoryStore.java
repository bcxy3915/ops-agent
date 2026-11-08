package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.chat.service.ConversationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 基于现有会话历史进行冷启动回填的 LangChain4j 记忆仓库。 */
public class ConversationChatMemoryStore implements ChatMemoryStore {

    private final ConversationService conversationService;
    private final int historyLimit;
    private final ConcurrentMap<Object, List<ChatMessage>> messagesByMemoryId = new ConcurrentHashMap<>();
    private final Set<Object> clearedMemoryIds = ConcurrentHashMap.newKeySet();

    /**
     * 创建会话记忆仓库。
     *
     * @param conversationService 现有会话历史服务
     * @param historyLimit 冷启动回填的最大消息数
     */
    public ConversationChatMemoryStore(ConversationService conversationService, int historyLimit) {
        this.conversationService = conversationService;
        this.historyLimit = historyLimit;
    }

    /**
     * 读取当前记忆；首次读取时按用户归属从数据库回填。
     *
     * @param memoryId 包含用户名和会话 ID 的记忆标识
     * @return 当前窗口内的聊天消息
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        checkTenant(memoryId);
        if (clearedMemoryIds.contains(memoryId)) {
            return List.of();
        }
        return messagesByMemoryId.computeIfAbsent(memoryId, this::loadHistory);
    }

    /**
     * 更新指定会话的内存消息窗口。
     *
     * @param memoryId 包含用户名和会话 ID 的记忆标识
     * @param messages LangChain4j 当前保留的窗口消息
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        checkTenant(memoryId);
        clearedMemoryIds.remove(memoryId);
        messagesByMemoryId.put(memoryId, List.copyOf(messages));
    }

    /**
     * 清理指定会话的记忆，不影响其他用户或会话。
     *
     * @param memoryId 包含用户名和会话 ID 的记忆标识
     */
    @Override
    public void deleteMessages(Object memoryId) {
        checkTenant(memoryId);
        messagesByMemoryId.remove(memoryId);
        clearedMemoryIds.add(memoryId);
    }

    /**
     * 校验记忆标识所属租户与当前执行上下文一致，缓存命中也不能绕过校验。
     * @param memoryId 含租户、用户名及会话的标识
     * @return 无返回值
     */
    private void checkTenant(Object memoryId) {
        var identity = LangChain4jMemoryId.parse(memoryId);
        if (!java.util.Objects.equals(identity.tenantId(),
                com.example.opsaiagent.rbac.context.TenantContext.get())) {
            throw new org.springframework.security.access.AccessDeniedException("会话记忆租户与当前上下文不一致");
        }
    }

    /**
     * 按当前租户及用户归属从数据库恢复历史。
     * @param memoryId 含租户、用户名及会话的标识
     * @return 按持久化顺序排列的历史消息
     */
    private List<ChatMessage> loadHistory(Object memoryId) {
        LangChain4jMemoryId.Identity identity = LangChain4jMemoryId.parse(memoryId);
        return LangChain4jMemoryHistoryMapper.toChatMessages(
                conversationService.loadRecentMessages(identity.sessionId(), identity.username(), historyLimit));
    }
}
