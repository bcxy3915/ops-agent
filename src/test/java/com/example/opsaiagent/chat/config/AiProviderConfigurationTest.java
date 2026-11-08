package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.*;
import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.StreamingChatGateway;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证默认配置即可组装唯一的 LangChain4j 生产链路。 */
class AiProviderConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LangChain4jConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ConversationService.class, () -> mock(ConversationService.class))
            .withBean(HybridRetriever.class, () -> mock(HybridRetriever.class))
            .withBean(HealthCheckTools.class, () -> mock(HealthCheckTools.class))
            .withBean(MetricQueryTools.class, () -> mock(MetricQueryTools.class))
            .withBean(MetricDiscoveryTools.class, () -> mock(MetricDiscoveryTools.class))
            .withBean(ServiceDiscoveryTools.class, () -> mock(ServiceDiscoveryTools.class))
            .withPropertyValues("ops-agent.ai.api-key=test-key", "ops-agent.ai.base-url=http://127.0.0.1:1/v1");

    /**
     * 验证不设置 provider 时三个模型网关均为 LangChain4j 实现。
     * @return 无返回值
     */
    @Test
    void defaultsToLangChain4jGateways() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ChatGateway.class);
            assertThat(context).hasSingleBean(StructuredOutputGateway.class);
            assertThat(context).hasSingleBean(StreamingChatGateway.class);
            assertThat(context.getBean(ChatGateway.class)).isInstanceOf(LangChain4jChatGateway.class);
            assertThat(context.getBean(StructuredOutputGateway.class)).isInstanceOf(LangChain4jStructuredOutputGateway.class);
            assertThat(context.getBean(StreamingChatGateway.class)).isInstanceOf(LangChain4jStreamingChatGateway.class);
        });
    }

    /**
     * 验证同步、流式助手及记忆仓库必须注册，不能通过可选注入跳过。
     * @return 无返回值
     */
    @Test
    void registersAgentsAndMemoryStore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpsAssistant.class);
            assertThat(context).hasSingleBean(OpsStreamingAssistant.class);
            assertThat(context).hasSingleBean(dev.langchain4j.store.memory.chat.ChatMemoryStore.class);
        });
    }

    /**
     * 验证仍携带历史 provider 值也不会启用旧框架。
     * @return 无返回值
     */
    @Test
    void obsoleteProviderCannotReactivateRemovedFramework() {
        runner.withPropertyValues("ops-agent.ai.provider=spring-ai").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpsAssistant.class);
            assertThat(context.getBean(ChatGateway.class)).isInstanceOf(LangChain4jChatGateway.class);
        });
    }
}
