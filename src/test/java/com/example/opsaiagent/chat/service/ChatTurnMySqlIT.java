package com.example.opsaiagent.chat.service;

import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 连接本地 MySQL 验证双实例租约；仅操作随机测试租户，不创建独立数据库。 */
@EnabledIfSystemProperty(named = "storage.live-test", matches = "true")
class ChatTurnMySqlIT {
    private JdbcTemplate jdbc;
    private ChatTurnService first;
    private ChatTurnService second;
    private final String tenant = "it-" + UUID.randomUUID().toString().substring(0, 20);

    /**
     * 连接 MySQL，准备两个共享数据库的模拟应用实例。
     * @return 无返回值，无入参
     */
    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:mysql://localhost:23306/opsdb?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "root", System.getenv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        var conversations = mock(ConversationService.class);
        doAnswer(call -> {
            jdbc.update("INSERT INTO ops_conversation(id, tenant_id, session_id, username) VALUES (?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE id=ops_conversation.id", UUID.randomUUID().toString(),
                    TenantContext.get(), call.getArgument(0), call.getArgument(1));
            return null;
        }).when(conversations).ensureConversation(anyString(), anyString(), anyString());
        var manager = new DataSourceTransactionManager(source);
        first = new ChatTurnService(jdbc, manager, conversations, 360, Duration.ofSeconds(300));
        second = new ChatTurnService(jdbc, manager, conversations, 360, Duration.ofSeconds(300));
        login();
    }

    /**
     * 清理当前测试租户记录，保留其他数据。
     * @return 无返回值，无入参
     */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ops_message WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM ops_conversation WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM ops_chat_turn WHERE tenant_id=?", tenant);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * 验证并发实例仅一方拿到租约，消息按轮次持久化。
     * @return 无返回值，无入参
     * @throws Exception 并发执行异常
     */
    @Test
    void concurrentInstancesSerializeTurnsAndMessages() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(() -> acquire(first, gate), () -> acquire(second, gate)));
            var values = List.of(results.get(0).get(), results.get(1).get());
            assertThat(values.stream().filter(ChatTurn.class::isInstance)).hasSize(1);
            assertThat(values.stream().filter(BusinessException.class::isInstance)).hasSize(1);
            ChatTurn winner = (ChatTurn) values.stream().filter(ChatTurn.class::isInstance).findFirst().orElseThrow();
            first.saveQuestion(winner, "第一问");
            first.finish(winner, "第一答");
            var next = second.begin("s", "第二问");
            second.saveQuestion(next, "第二问");
            second.finish(next, "第二答");
            assertThat(jdbc.queryForList("SELECT content FROM ops_message WHERE tenant_id=? ORDER BY id", String.class, tenant))
                    .containsExactly("第一问", "第一答", "第二问", "第二答");
        }
    }

    /**
     * 验证过期接管、删除重建后旧令牌不能回写或释放新租约。
     * @return 无返回值，无入参
     */
    @Test
    void expiredAndDeletedGenerationsAreFenced() {
        var old = first.begin("s", "第一问");
        first.saveQuestion(old, "第一问");
        jdbc.update("UPDATE ops_chat_turn SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE tenant_id=?", tenant);
        var replacement = second.begin("s", "接管问");
        assertThatThrownBy(() -> first.finish(old, "旧答案")).isInstanceOf(BusinessException.class);
        second.assertActive(replacement);
        jdbc.update("DELETE FROM ops_message WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM ops_conversation WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM ops_chat_turn WHERE tenant_id=?", tenant);
        var recreated = first.begin("s", "重建问");
        assertThat(recreated.conversationId()).isNotEqualTo(replacement.conversationId());
        assertThatThrownBy(() -> second.finish(replacement, "旧答案")).isInstanceOf(BusinessException.class);
        first.saveQuestion(recreated, "重建问");
        first.finish(recreated, "重建答");
        assertThat(jdbc.queryForList("SELECT content FROM ops_message WHERE tenant_id=? ORDER BY id", String.class, tenant))
                .containsExactly("重建问", "重建答");
    }

    /**
     * 在并发线程中设置上下文并争用租约。
     * @param service 应用实例
     * @param gate 起跑屏障
     * @return 轮次或业务拒绝
     * @throws Exception 并发异常
     */
    private Object acquire(ChatTurnService service, CyclicBarrier gate) throws Exception {
        login();
        try {
            gate.await();
            try { return service.begin("s", "问题"); }
            catch (BusinessException failure) { return failure; }
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 设置测试身份。
     * @return 无返回值，无入参
     */
    private void login() {
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    }
}
