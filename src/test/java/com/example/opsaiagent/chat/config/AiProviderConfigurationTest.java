package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jChatGateway;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStructuredOutputGateway;
import com.example.opsaiagent.ai.adapter.springai.SpringAiChatGateway;
import com.example.opsaiagent.ai.adapter.springai.SpringAiStructuredOutputGateway;
import com.example.opsaiagent.ai.adapter.springai.SpringAiChatGatewayConfig;
import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
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
            assertThat(context.getBean(ChatGateway.class)).isInstanceOf(SpringAiChatGateway.class);
            assertThat(context.getBean(StructuredOutputGateway.class))
                    .isInstanceOf(SpringAiStructuredOutputGateway.class);
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
                    assertThat(context.getBean(ChatGateway.class)).isInstanceOf(LangChain4jChatGateway.class);
                    assertThat(context.getBean(StructuredOutputGateway.class))
                            .isInstanceOf(LangChain4jStructuredOutputGateway.class);
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
