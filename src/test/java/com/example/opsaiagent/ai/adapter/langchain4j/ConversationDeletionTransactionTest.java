package com.example.opsaiagent.ai.adapter.langchain4j;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import com.example.opsaiagent.chat.event.ConversationDeletedEvent;
import com.example.opsaiagent.chat.service.ConversationService;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 使用内存事务夹具验证删除提交与回滚对记忆的影响，不访问数据库。 */
class ConversationDeletionTransactionTest {

    /**
     * 验证删除回滚不清缓存，提交后同一会话不再保留历史。
     * @return 无返回值
     */
    @Test
    void clearsMemoryOnlyAfterDeletionCommits() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(ConversationChatMemoryStore.class,
                    () -> new ConversationChatMemoryStore(mock(ConversationService.class), 20));
            context.refresh();
            var store = context.getBean(ConversationChatMemoryStore.class);
            var id = LangChain4jMemoryId.of("alice", "deleted-session");
            store.updateMessages(id, List.of(new UserMessage("应在删除提交后清理的历史")));
            var transaction = new TransactionTemplate(new MemoryTransactionManager());

            transaction.executeWithoutResult(status -> {
                context.publishEvent(new ConversationDeletedEvent(id));
                assertThat(store.getMessages(id)).isNotEmpty();
                status.setRollbackOnly();
            });
            assertThat(store.getMessages(id)).isNotEmpty();

            transaction.executeWithoutResult(status -> context.publishEvent(new ConversationDeletedEvent(id)));
            assertThat(store.getMessages(id)).isEmpty();
        }
    }

    /** 仅管理 Spring 事务同步回调的测试夹具。 */
    static class MemoryTransactionManager extends AbstractPlatformTransactionManager {
        /**
         * 创建测试事务状态。
         * @return 测试事务标识
         */
        @Override
        protected Object doGetTransaction() { return new Object(); }

        /**
         * 开始测试事务。
         * @param transaction 测试事务标识
         * @param definition 事务定义
         * @return 无返回值
         */
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        /**
         * 提交测试事务，实际同步回调由父类触发。
         * @param status 事务状态
         * @return 无返回值
         */
        @Override
        protected void doCommit(DefaultTransactionStatus status) { }

        /**
         * 回滚测试事务。
         * @param status 事务状态
         * @return 无返回值
         */
        @Override
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}
