package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.chat.service.ChatTurnService;
import com.example.opsaiagent.chat.service.OpsAgentService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import com.example.opsaiagent.ai.adapter.langchain4j.*;
import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.ai.api.StreamingChatGateway;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证默认配置即可组装唯一的 LangChain4j 生产链路。 */
class AiProviderConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(LangChain4jConfig.class,
                    OpsAgentService.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ConversationService.class, () -> mock(ConversationService.class))
            .withBean(ChatTurnService.class,
                    () -> mock(ChatTurnService.class))
            .withBean(KnowledgeRetrievalService.class, () -> mock(KnowledgeRetrievalService.class))
            .withBean(KnowledgeHitLogService.class, () -> mock(KnowledgeHitLogService.class))
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
            assertThat(context).hasSingleBean(OpsAgentService.class);
            assertThat(context).hasSingleBean(ChatMemoryStore.class);
        });
    }

    /**
     * 验证同步与流式模型使用相同的项目模型及温度配置。
     * @return 无返回值
     */
    @Test
    void syncAndStreamShareModelAndTemperature() {
        runner.withPropertyValues("ops-agent.ai.model=audit-model", "ops-agent.ai.temperature=0.25")
                .run(context -> {
                    var sync = context.getBean(OpenAiChatModel.class)
                            .defaultRequestParameters();
                    var stream = context.getBean(OpenAiStreamingChatModel.class)
                            .defaultRequestParameters();
                    assertThat(sync.modelName()).isEqualTo("audit-model");
                    assertThat(stream.modelName()).isEqualTo(sync.modelName());
                    assertThat(sync.temperature()).isEqualTo(0.25);
                    assertThat(stream.temperature()).isEqualTo(sync.temperature());
                });
    }

    /**
     * 验证历史本地配置的模型名和温度在未配置项目项时继续生效。
     * @return 无返回值
     */
    @Test
    void legacyLocalModelAndTemperatureRemainCompatible() {
        runner.withPropertyValues("spring.ai.openai.chat.model=legacy-model",
                        "spring.ai.openai.chat.temperature=0.15")
                .run(context -> {
                    assertThat(context.getBean(OpenAiChatModel.class)
                            .defaultRequestParameters().modelName()).isEqualTo("legacy-model");
                    assertThat(context.getBean(OpenAiStreamingChatModel.class)
                            .defaultRequestParameters().temperature()).isEqualTo(0.15);
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
