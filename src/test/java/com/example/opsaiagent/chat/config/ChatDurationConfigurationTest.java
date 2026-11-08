package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.ConversationChatMemoryStore;
import com.example.opsaiagent.chat.service.ChatTurnService;
import com.example.opsaiagent.chat.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 直接装配生产记忆工厂与轮次服务，验证时长注入，不创建 HTTP 客户端或连接数据库。 */
class ChatDurationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var factory = context.getBeanFactory();
                factory.setConversionService(ApplicationConversionService.getSharedInstance());
                // 只注册实际记忆工厂方法，避免其他模型 Bean 的网络资源影响配置绑定验证。
                factory.registerSingleton("memoryFactory", new LangChain4jConfig());
                var definition = new RootBeanDefinition();
                definition.setAutowireMode(RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                definition.setFactoryBeanName("memoryFactory");
                definition.setFactoryMethodName("langChain4jChatMemoryStore");
                ((BeanDefinitionRegistry) factory).registerBeanDefinition("memoryStore", definition);
            })
            .withBean(ConversationService.class, () -> mock(ConversationService.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ChatTurnService.class);

    /**
     * 验证生产默认的秒数时长可注入记忆工厂与轮次构造器。
     * @return 无返回值
     */
    @Test
    void defaultDurationsAreInjectedIntoProductionBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConversationChatMemoryStore.class);
            assertThat(context.getBean(ChatTurnService.class).timeout()).isEqualTo(Duration.ofSeconds(300));
        });
    }

    /**
     * 验证覆盖配置同时支持分钟和 ISO 时长格式。
     * @return 无返回值
     */
    @Test
    void customDurationFormatsAreSupported() {
        runner.withPropertyValues("ops-agent.chat.memory.ttl=10m", "ops-agent.chat.turn.timeout=PT4M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatTurnService.class).timeout()).isEqualTo(Duration.ofMinutes(4));
                });
    }

    /**
     * 验证时长转换后仍拒绝缓存过期时间不大于整轮超时的配置。
     * @return 无返回值
     */
    @Test
    void invalidTtlIsRejectedByProductionValidation() {
        runner.withPropertyValues("ops-agent.chat.memory.ttl=300s", "ops-agent.chat.turn.timeout=5m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("记忆过期时间必须大于整轮超时");
                });
    }
}
