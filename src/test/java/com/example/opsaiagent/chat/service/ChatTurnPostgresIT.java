package com.example.opsaiagent.chat.service;

import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 仅连接显式指定的独立测试库；每个用例使用独立 schema，不读取本地真实配置。 */
@EnabledIfSystemProperty(named = "chat.test.jdbc-url", matches = ".+")
class ChatTurnPostgresIT {
    private JdbcTemplate jdbc;
    private JdbcTemplate admin;
    private ChatTurnService first;
    private ChatTurnService second;
    private TransactionTemplate transaction;
    private String schema;

    /**
     * 在独立 schema 中加载生产租约迁移，创建最小会话及消息表。
     * @return 无返回值
     */
    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(System.getProperty("chat.test.jdbc-url"),
                System.getProperty("chat.test.username", "postgres"), System.getProperty("chat.test.password", ""));
        admin = new JdbcTemplate(source);
        schema = "chat_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String url = System.getProperty("chat.test.jdbc-url");
        var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getProperty("chat.test.username", "postgres"), System.getProperty("chat.test.password", ""));
        jdbc = new JdbcTemplate(scoped);
        new ResourceDatabasePopulator(new FileSystemResource("init-scripts/migrations/20261108-chat-turn.sql"))
                .execute(scoped);
        jdbc.execute("""
                CREATE TABLE ops_conversation (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(32),
                  session_id VARCHAR(64), username VARCHAR(64), message_count INTEGER DEFAULT 0,
                  last_active_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(tenant_id, session_id))
                """);
        jdbc.execute("""
                CREATE TABLE ops_message (id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(32), session_id VARCHAR(64),
                  role VARCHAR(16), content TEXT, created_at TIMESTAMP)
                """);
        var conversations = mock(ConversationService.class);
        doAnswer(call -> {
            String tenant = TenantContext.get();
            String session = call.getArgument(0);
            String username = call.getArgument(1);
            jdbc.update("INSERT INTO ops_conversation(id, tenant_id, session_id, username) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (tenant_id, session_id) DO NOTHING", UUID.randomUUID().toString(), tenant, session, username);
            return null;
        }).when(conversations).ensureConversation(anyString(), anyString(), anyString());
        var manager = new DataSourceTransactionManager(scoped);
        transaction = new TransactionTemplate(manager);
        first = new ChatTurnService(jdbc, manager, conversations, 360, Duration.ofSeconds(300));
        second = new ChatTurnService(jdbc, manager, conversations, 360, Duration.ofSeconds(300));
        login();
    }

    /**
     * 清理且仅清理本用例生成的独立 schema。
     * @return 无返回值
     */
    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        if (admin != null && schema != null) { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    /**
     * 验证两个实例同时提问仅一方获取租约，提交答案后下一轮可安全开始。
     * @return 无返回值
     * @throws Exception 并发任务执行失败
     */
    @Test
    void concurrentInstancesSerializeTurnsAndMessages() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(
                    () -> acquire(first, gate), () -> acquire(second, gate)));
            var values = List.of(results.get(0).get(), results.get(1).get());
            assertThat(values.stream().filter(ChatTurn.class::isInstance)).hasSize(1);
            assertThat(values.stream().filter(BusinessException.class::isInstance)).hasSize(1);
            ChatTurn winner = (ChatTurn) values.stream().filter(ChatTurn.class::isInstance).findFirst().orElseThrow();
            first.saveQuestion(winner, "第一问");
            first.finish(winner, "第一答");
            ChatTurn next = second.begin("s", "第二问");
            second.saveQuestion(next, "第二问");
            second.finish(next, "第二答");
            assertThat(jdbc.queryForList("SELECT content FROM ops_message ORDER BY id", String.class))
                    .containsExactly("第一问", "第一答", "第二问", "第二答");
            assertThat(jdbc.queryForObject("SELECT message_count FROM ops_conversation", Integer.class)).isEqualTo(4);
        }
    }

    /**
     * 验证过期接管后旧请求不能写回或释放新租约，删除重建也不能复活旧答案。
     * @return 无返回值
     */
    @Test
    void expiredAndDeletedGenerationsAreFenced() {
        ChatTurn old = first.begin("s", "第一问");
        first.saveQuestion(old, "第一问");
        jdbc.update("UPDATE ops_chat_turn SET expires_at=clock_timestamp()-interval '1 second'");
        ChatTurn replacement = second.begin("s", "接管问");
        assertThatThrownBy(() -> first.finish(old, "不应保存的旧答案")).isInstanceOf(BusinessException.class);
        second.assertActive(replacement);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT * FROM ops_chat_turn FOR UPDATE");
            jdbc.queryForList("SELECT * FROM ops_conversation FOR UPDATE");
            jdbc.update("DELETE FROM ops_conversation");
            jdbc.update("DELETE FROM ops_message");
            jdbc.update("DELETE FROM ops_chat_turn");
        });
        ChatTurn recreated = first.begin("s", "重建问");
        assertThat(recreated.conversationId()).isNotEqualTo(replacement.conversationId());
        assertThatThrownBy(() -> second.finish(replacement, "不应复活的答案")).isInstanceOf(BusinessException.class);
        first.assertActive(recreated);
        first.saveQuestion(recreated, "重建问");
        first.finish(recreated, "重建答");
        assertThat(jdbc.queryForList("SELECT content FROM ops_message ORDER BY id", String.class))
                .containsExactly("重建问", "重建答");
    }

    /**
     * 为并发线程设置可信上下文并同步争用数据库租约。
     * @param service 模拟实例
     * @param gate 同步起跑屏障
     * @return 成功轮次或被拒绝的异常
     * @throws Exception 屏障等待失败
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
     * 设置测试租户与认证，无真实凭据。
     * @return 无返回值
     */
    private void login() {
        TenantContext.set("test-tenant");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    }
}
