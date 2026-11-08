package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.knowledge.model.Document;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** 有界的轮次记忆：每轮从数据库刷新，活动轮次不被容量淘汰，结束立即清理。 */
public class ConversationChatMemoryStore implements ChatMemoryStore {
    private final ConversationService conversations;
    private final int historyLimit;
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier ticker;
    private final Map<Object, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * 创建默认有界记忆仓库。
     * @param conversations 持久化会话服务
     * @param historyLimit 历史消息窗口
     * @return 无返回值
     */
    public ConversationChatMemoryStore(ConversationService conversations, int historyLimit) {
        this(conversations, historyLimit, 1000, Duration.ofMinutes(10), System::nanoTime);
    }

    /**
     * 创建可配置并可测试过期行为的记忆仓库。
     * @param conversations 持久化会话服务
     * @param historyLimit 历史消息窗口
     * @param capacity 最大缓存项数，包含活动轮次
     * @param ttl 缓存过期时间
     * @param ticker 单调时钟
     * @return 无返回值
     */
    public ConversationChatMemoryStore(ConversationService conversations, int historyLimit, int capacity,
                                       Duration ttl, LongSupplier ticker) {
        if (historyLimit <= 0 || capacity <= 0 || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("记忆窗口、容量和过期时间必须大于零");
        }
        this.conversations = conversations;
        this.historyLimit = historyLimit;
        this.capacity = capacity;
        this.ttlNanos = ttl.toNanos();
        this.ticker = ticker;
    }

    /**
     * 打开一次独立轮次并从数据库刷新，必须先于当前问题保存。
     * @param memoryId 含请求令牌的记忆标识
     * @param activeCheck 持久化租约与会话代次检查
     * @return 无返回值
     */
    public synchronized void openTurn(String memoryId, Runnable activeCheck) {
        checkTenant(memoryId);
        activeCheck.run();
        makeRoom(memoryId);
        entries.put(memoryId, new Entry(loadHistory(memoryId), ticker.getAsLong(), activeCheck));
    }

    /**
     * 绑定本轮唯一的检索快照，模型和引用共同消费这份结果。
     * @param memoryId 本轮记忆标识
     * @param documents 本轮已授权检索结果
     * @return 无返回值
     */
    public synchronized void bindDocuments(String memoryId, List<Document> documents) {
        Entry entry = required(memoryId);
        entry.documents = documents.stream().filter(d -> d != null && d.getText() != null && !d.getText().isBlank())
                .map(d -> new Document(d.getId(), d.getText(), d.getMetadata(), d.getScore())).toList();
    }

    /**
     * 返回本轮检索快照，未绑定时拒绝回退到第二次检索。
     * @param memoryId 本轮记忆标识
     * @return 同一组检索文档的防御性副本
     */
    public synchronized List<Document> documents(Object memoryId) {
        Entry entry = required(memoryId);
        if (entry.documents == null) { throw new IllegalStateException("问答检索快照尚未绑定"); }
        return entry.documents.stream()
                .map(d -> new Document(d.getId(), d.getText(), d.getMetadata(), d.getScore())).toList();
    }

    /**
     * 读取窗口并检查活动轮次；已结束的请求不得重新加载或复活缓存。
     * @param memoryId 用户、租户、会话及可选请求标识
     * @return 当前消息窗口
     */
    @Override
    public synchronized List<ChatMessage> getMessages(Object memoryId) {
        checkTenant(memoryId);
        purge();
        if (isTurn(memoryId)) { return required(memoryId).messages; }
        Entry entry = entries.get(memoryId);
        if (entry == null) {
            makeRoom(memoryId);
            entry = new Entry(loadHistory(memoryId), ticker.getAsLong(), null);
            entries.put(memoryId, entry);
        }
        entry.accessed = ticker.getAsLong();
        return entry.messages;
    }

    /**
     * 更新窗口；请求关闭后来的异步回调会被拒绝，不能污染新轮次。
     * @param memoryId 记忆标识
     * @param messages 模型窗口消息
     * @return 无返回值
     */
    @Override
    public synchronized void updateMessages(Object memoryId, List<ChatMessage> messages) {
        checkTenant(memoryId);
        if (isTurn(memoryId)) {
            required(memoryId).messages = List.copyOf(messages);
        } else {
            makeRoom(memoryId);
            entries.put(memoryId, new Entry(List.copyOf(messages), ticker.getAsLong(), null));
        }
    }

    /**
     * 清空指定窗口，空窗口同样受容量和过期限制，不累积永久清理标记。
     * @param memoryId 记忆标识
     * @return 无返回值
     */
    @Override
    public synchronized void deleteMessages(Object memoryId) {
        checkTenant(memoryId);
        if (isTurn(memoryId)) {
            Entry entry = entries.get(memoryId);
            if (entry != null) { entry.messages = List.of(); }
        } else {
            makeRoom(memoryId);
            entries.put(memoryId, new Entry(List.of(), ticker.getAsLong(), null));
        }
    }

    /**
     * 释放轮次缓存，晚到回调不能再创建同一轮次。
     * @param memoryId 本轮标识
     * @return 无返回值
     */
    public synchronized void closeTurn(String memoryId) { entries.remove(memoryId); }

    /**
     * 删除提交后清理本机对应会话的所有窗口，其他实例依靠持久化令牌校验失效。
     * @param event 会话删除事件
     * @return 无返回值
     */
    @org.springframework.transaction.event.TransactionalEventListener
    public synchronized void onConversationDeleted(com.example.opsaiagent.chat.event.ConversationDeletedEvent event) {
        entries.keySet().removeIf(id -> id.equals(event.memoryId()) || id.toString().startsWith(event.memoryId() + "."));
    }

    /**
     * 返回清理过期项后的缓存数量。
     * @return 当前缓存项数
     */
    public synchronized int size() { purge(); return entries.size(); }

    /**
     * 读取活动项，并执行数据库令牌校验。
     * @param id 本轮标识
     * @return 有效缓存项
     */
    private Entry required(Object id) {
        checkTenant(id);
        purge();
        Entry entry = entries.get(id);
        if (entry == null) { throw new IllegalStateException("问答记忆已结束或过期"); }
        if (entry.activeCheck != null) { entry.activeCheck.run(); }
        entry.accessed = ticker.getAsLong();
        return entry;
    }

    /**
     * 校验缓存归属。
     * @param id 记忆标识
     * @return 无返回值
     */
    private void checkTenant(Object id) {
        if (!java.util.Objects.equals(LangChain4jMemoryId.parse(id).tenantId(),
                com.example.opsaiagent.rbac.context.TenantContext.get())) {
            throw new org.springframework.security.access.AccessDeniedException("会话记忆租户与当前上下文不一致");
        }
    }

    /**
     * 判断是否为一次性轮次标识。
     * @param id 记忆标识
     * @return 是否包含请求令牌
     */
    private boolean isTurn(Object id) { return id.toString().split("\\.", -1).length == 4; }

    /**
     * 清理过期项；活动项采用创建时间，普通缓存采用最近访问时间。
     * @return 无返回值
     */
    private void purge() {
        long now = ticker.getAsLong();
        entries.values().removeIf(e -> now - (e.activeCheck == null ? e.accessed : e.created) >= ttlNanos);
    }

    /**
     * 容量已满时淘汰最久未访问的非活动项，全是活动项则拒绝新请求。
     * @param id 即将写入的标识
     * @return 无返回值
     */
    private void makeRoom(Object id) {
        purge();
        if (entries.containsKey(id)) { return; }
        if (entries.size() >= capacity) {
            var victim = entries.entrySet().stream().filter(e -> e.getValue().activeCheck == null).findFirst();
            if (victim.isEmpty()) { throw new IllegalStateException("问答并发容量已满，请稍后重试"); }
            entries.remove(victim.get().getKey());
        }
    }

    /**
     * 按当前租户和会话归属读取持久化历史。
     * @param memoryId 记忆标识
     * @return 数据库消息窗口
     */
    private List<ChatMessage> loadHistory(Object memoryId) {
        var identity = LangChain4jMemoryId.parse(memoryId);
        return LangChain4jMemoryHistoryMapper.toChatMessages(
                conversations.loadRecentMessages(identity.sessionId(), identity.username(), historyLimit));
    }

    /** 单个有界窗口，活动回调只存在于本轮生命周期内。 */
    private static final class Entry {
        private List<ChatMessage> messages;
        private List<Document> documents;
        private final long created;
        private long accessed;
        private final Runnable activeCheck;
        /**
         * 创建缓存项。
         * @param messages 消息窗口
         * @param now 当前单调时钟
         * @param activeCheck 活动令牌校验
         * @return 无返回值
         */
        private Entry(List<ChatMessage> messages, long now, Runnable activeCheck) {
            this.messages = List.copyOf(messages);
            this.created = now;
            this.accessed = now;
            this.activeCheck = activeCheck;
        }
    }
}
