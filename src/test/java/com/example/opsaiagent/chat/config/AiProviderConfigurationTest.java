package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStructuredOutputGateway;
import com.example.opsaiagent.ai.adapter.springai.SpringAiChatGateway;
import com.example.opsaiagent.ai.adapter.springai.SpringAiStructuredOutputGateway;
import com.example.opsaiagent.ai.adapter.springai.SpringAiChatGatewayConfig;
import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import com.example.opsaiagent.ai.api.StreamingChatGateway;
import com.example.opsaiagent.ai.adapter.springai.SpringAiStreamingChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStreamingChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class, LangChain4jConfig.class,
                    SpringAiChatGatewayConfig.class, TestDependencies.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultsToSpringAiGateways() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatGateway.class);
            assertThat(context).hasSingleBean(StructuredOutputGateway.class);
            assertThat(context).hasSingleBean(StreamingChatGateway.class);
            assertThat(context.getBean(ChatGateway.class)).isInstanceOf(SpringAiChatGateway.class);
            assertThat(context.getBean(StructuredOutputGateway.class))
                    .isInstanceOf(SpringAiStructuredOutputGateway.class);
            assertThat(context.getBean(StreamingChatGateway.class)).isInstanceOf(SpringAiStreamingChatGateway.class);
        });
    }

    @Test
    void selectsLangChain4jGatewaysWhenConfigured() {
        contextRunner.withPropertyValues(
                "ops-agent.ai.provider=langchain4j",
                "ops-agent.ai.base-url=http://127.0.0.1:1/v1",
                "ops-agent.ai.api-key=test-key",
                "ops-agent.ai.model=test-model")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatGateway.class);
                    assertThat(context).hasSingleBean(StructuredOutputGateway.class);
                    assertThat(context).hasSingleBean(StreamingChatGateway.class);
                    assertThat(context.getBean(ChatGateway.class)).isInstanceOf(LangChain4jChatGateway.class);
                    assertThat(context.getBean(StructuredOutputGateway.class))
                            .isInstanceOf(LangChain4jStructuredOutputGateway.class);
                    assertThat(context.getBean(StreamingChatGateway.class))
                            .isInstanceOf(LangChain4jStreamingChatGateway.class);
                });
    }

    /**
     * 验证生产依赖齐备时同步与流式 Agent 均实际注册。
     * @return 无返回值
     */
    @Test
    void registersBothAgentsWithRetrievalAndToolDependencies() {
        contextRunner
                .withBean(com.example.opsaiagent.chat.service.ConversationService.class,
                        () -> mock(com.example.opsaiagent.chat.service.ConversationService.class))
                .withBean(com.example.opsaiagent.retrieval.HybridRetriever.class,
                        () -> mock(com.example.opsaiagent.retrieval.HybridRetriever.class))
                .withBean(com.example.opsaiagent.tools.HealthCheckTools.class,
                        () -> mock(com.example.opsaiagent.tools.HealthCheckTools.class))
                .withBean(com.example.opsaiagent.tools.MetricQueryTools.class,
                        () -> mock(com.example.opsaiagent.tools.MetricQueryTools.class))
                .withBean(com.example.opsaiagent.tools.MetricDiscoveryTools.class,
                        () -> mock(com.example.opsaiagent.tools.MetricDiscoveryTools.class))
                .withBean(com.example.opsaiagent.tools.ServiceDiscoveryTools.class,
                        () -> mock(com.example.opsaiagent.tools.ServiceDiscoveryTools.class))
                .withPropertyValues("ops-agent.ai.provider=langchain4j", "ops-agent.ai.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.example.opsaiagent.ai.adapter.langchain4j.OpsAssistant.class);
                    assertThat(context).hasSingleBean(
                            com.example.opsaiagent.ai.adapter.langchain4j.OpsStreamingAssistant.class);
                });
    }
    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        org.springframework.ai.chat.model.ChatModel springAiChatModel() {
            return mock(org.springframework.ai.chat.model.ChatModel.class);
        }

        @Bean
        ChatMemory chatMemory() {
            return mock(ChatMemory.class);
        }
    }
}
