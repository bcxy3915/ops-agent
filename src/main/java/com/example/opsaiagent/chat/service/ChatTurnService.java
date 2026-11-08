package com.example.opsaiagent.chat.service;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jMemoryId;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.security.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.UUID;

/** PostgreSQL 问答租约：跨实例互斥、会话代次校验及同步持久化，模型调用不占数据库事务。 */
@Service
public class ChatTurnService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversationService conversations;
    private final int leaseSeconds;
    private final Duration timeout;

    /**
     * 创建分布式问答轮次服务。
     * @param jdbc 数据库客户端
     * @param manager 数据源事务管理器
     * @param conversations 会话服务
     * @param leaseSeconds 租约秒数，必须覆盖整轮超时
     * @param timeout 整轮最长时间
     * @return 无返回值
     */
    public ChatTurnService(JdbcTemplate jdbc, PlatformTransactionManager manager, ConversationService conversations,
                           @Value("${ops-agent.chat.turn.lease-seconds:360}") int leaseSeconds,
                           @Value("${ops-agent.chat.turn.timeout:300s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || leaseSeconds <= timeout.toSeconds()) {
            throw new IllegalArgumentException("问答租约必须大于整轮超时且超时大于零");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.conversations = conversations;
        this.leaseSeconds = leaseSeconds;
        this.timeout = timeout;
    }

    /**
     * 原子领取会话轮次并校验用户归属；重复请求立即拒绝，过期租约可被新请求替代。
     * @param sessionId 会话标识
     * @param question 当前问题
     * @return 本轮令牌
     */
    public ChatTurn begin(String sessionId, String question) {
        String tenant = TenantContext.get();
        String username = SecurityUtils.getCurrentUsername();
        if (tenant == null || username == null) {
            throw new BusinessException(ErrorCode.TENANT_REQUIRED, "问答需要登录用户和租户上下文");
        }
        String request = UUID.randomUUID().toString();
        return transactions.execute(status -> {
            int acquired = jdbc.update("""
                    INSERT INTO ops_chat_turn (tenant_id, session_id, request_id, expires_at)
                    VALUES (?, ?, ?, clock_timestamp() + (? * interval '1 second'))
                    ON CONFLICT (tenant_id, session_id) DO UPDATE
                    SET request_id = EXCLUDED.request_id, expires_at = EXCLUDED.expires_at
                    WHERE ops_chat_turn.expires_at <= clock_timestamp()
                    """, tenant, sessionId, request, leaseSeconds);
            if (acquired != 1) {
                throw new BusinessException(ErrorCode.CHAT_TURN_BUSY, "该会话正在回答，请完成或停止后重试");
            }
            conversations.ensureConversation(sessionId, username, question);
            String conversation = jdbc.queryForObject("""
                    SELECT id FROM ops_conversation WHERE tenant_id = ? AND session_id = ? AND username = ? FOR UPDATE
                    """, String.class, tenant, sessionId, username);
            jdbc.update("UPDATE ops_chat_turn SET conversation_id = ? WHERE tenant_id = ? AND session_id = ? AND request_id = ?",
                    conversation, tenant, sessionId, request);
            return new ChatTurn(tenant, sessionId, username, request, conversation,
                    LangChain4jMemoryId.of(username, sessionId) + "." + request);
        });
    }

    /**
     * 校验令牌、租约及会话代次，删除、重建和租约抢占都会使旧请求失效。
     * @param turn 本轮令牌
     * @return 无返回值
     */
    public void assertActive(ChatTurn turn) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ops_chat_turn t JOIN ops_conversation c
                  ON c.tenant_id=t.tenant_id AND c.session_id=t.session_id AND c.id=t.conversation_id
                WHERE t.tenant_id=? AND t.session_id=? AND t.request_id=? AND c.id=? AND c.username=?
                  AND t.expires_at > clock_timestamp())
                """, Boolean.class, turn.tenantId(), turn.sessionId(), turn.requestId(),
                turn.conversationId(), turn.username());
        if (!Boolean.TRUE.equals(active)) {
            throw new BusinessException(ErrorCode.CHAT_TURN_EXPIRED, "会话已删除、重建或本轮问答已过期");
        }
    }

    /**
     * 在写入用户问题前校验令牌；与删除共享行锁，避免用户问题成为孤儿消息。
     * @param turn 本轮令牌
     * @param question 当前问题
     * @return 无返回值
     */
    public void saveQuestion(ChatTurn turn, String question) {
        transactions.executeWithoutResult(status -> {
            lockAndValidate(turn);
            insertMessage(turn, "USER", question);
        });
    }

    /**
     * 同步保存完整或部分答案后释放租约；旧令牌不得写入，也不能释放新请求的租约。
     * @param turn 本轮令牌
     * @param answer 完整或部分答案
     * @return 无返回值
     */
    public void finish(ChatTurn turn, String answer) {
        try {
            transactions.executeWithoutResult(status -> {
                lockAndValidate(turn);
                if (answer != null && !answer.isEmpty()) {
                    insertMessage(turn, "ASSISTANT", answer);
                }
                release(turn);
            });
        } finally {
            release(turn);
        }
    }

    /**
     * 清理失败轮次，只删除自己持有的令牌。
     * @param turn 本轮令牌
     * @return 无返回值
     */
    public void release(ChatTurn turn) {
        jdbc.update("DELETE FROM ops_chat_turn WHERE tenant_id=? AND session_id=? AND request_id=?",
                turn.tenantId(), turn.sessionId(), turn.requestId());
    }

    /**
     * 获取整轮超时，流式处理据此取消并清理资源。
     * @return 整轮最长时间
     */
    public Duration timeout() { return timeout; }

    /**
     * 固定按租约、会话顺序加锁，再核验代次；删除事务使用相同顺序。
     * @param turn 本轮令牌
     * @return 无返回值
     */
    private void lockAndValidate(ChatTurn turn) {
        jdbc.queryForList("SELECT request_id FROM ops_chat_turn WHERE tenant_id=? AND session_id=? FOR UPDATE",
                turn.tenantId(), turn.sessionId());
        jdbc.queryForList("SELECT id FROM ops_conversation WHERE tenant_id=? AND session_id=? FOR UPDATE",
                turn.tenantId(), turn.sessionId());
        assertActive(turn);
    }

    /**
     * 在已验证并锁定的会话中插入消息，并原子递增统计。
     * @param turn 本轮令牌
     * @param role 消息角色
     * @param content 消息正文
     * @return 无返回值
     */
    private void insertMessage(ChatTurn turn, String role, String content) {
        jdbc.update("INSERT INTO ops_message (tenant_id, session_id, role, content, created_at) VALUES (?, ?, ?, ?, clock_timestamp())",
                turn.tenantId(), turn.sessionId(), role, content);
        jdbc.update("""
                UPDATE ops_conversation SET message_count=COALESCE(message_count,0)+1,
                  last_active_at=clock_timestamp(), updated_at=clock_timestamp()
                WHERE id=? AND tenant_id=?
                """, turn.conversationId(), turn.tenantId());
    }
}
