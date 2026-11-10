package com.example.opsaiagent.knowledge.config;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jElasticsearchStore;
import com.example.opsaiagent.retrieval.elasticsearch.ElasticsearchGateway;
import com.example.opsaiagent.retrieval.elasticsearch.SearchProjectionService;
import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证向量配置独立于聊天 provider，不连接真实模型和数据库。 */
class LangChain4jEmbeddingConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(LangChain4jEmbeddingConfig.class)
            .withBean(ElasticsearchGateway.class, () -> mock(ElasticsearchGateway.class))
            .withBean(SearchProjectionService.class, () -> mock(SearchProjectionService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("spring.ai.openai.embedding.api-key=test-key",
                    "ops-agent.ai.embedding.dimensions=3", "ops-agent.ai.vector-store.dimensions=3");

    /**
     * 验证默认聊天配置同样注册唯一的项目向量存储。
     * @return 无返回值
     */
    @Test
    void defaultConfigurationUsesLangChain4jStore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context).hasSingleBean(KnowledgeVectorStore.class);

            assertThat(context.getBean(KnowledgeVectorStore.class)).isInstanceOf(
                    LangChain4jElasticsearchStore.class);
        });
    }

    /**
     * 验证两种聊天 provider 均不会影响向量模型及存储注册。
     * @return 无返回值
     */
    @Test
    void chatProviderDoesNotChangeStorage() {
        for (String provider : new String[]{"spring-ai", "langchain4j"}) {
            runner.withPropertyValues("ops-agent.ai.provider=" + provider).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(KnowledgeVectorStore.class);
                assertThat(context).hasSingleBean(EmbeddingModel.class);
            });
        }
    }

    /**
     * 验证维度配置不一致时启动失败。
     * @return 无返回值
     */
    @Test
    void incompatibleDimensionsFailAtStartup() {
        runner.withPropertyValues("ops-agent.ai.embedding.dimensions=4").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("向量模型维度必须与现有 ES 配置一致");
        });
    }

    /**
     * 验证不再容忍缺失数据库配置后静默回退。
     * @return 无返回值
     */
    @Test
    void missingDatabaseFailsAtStartup() {
        new ApplicationContextRunner().withUserConfiguration(LangChain4jEmbeddingConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("ops-agent.ai.embedding.api-key=test-key")
                .run(context -> assertThat(context).hasFailed());
    }

}
