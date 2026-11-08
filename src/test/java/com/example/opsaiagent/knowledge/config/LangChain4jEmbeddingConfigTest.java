package com.example.opsaiagent.knowledge.config;

import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证向量配置独立于聊天 provider，不连接真实模型和数据库。 */
class LangChain4jEmbeddingConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LangChain4jEmbeddingConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("spring.ai.openai.embedding.api-key=test-key",
                    "spring.ai.vectorstore.pgvector.dimensions=3");

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
                    com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jPgVectorStore.class);
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
            assertThat(context.getStartupFailure()).hasRootCauseMessage("向量模型维度必须与现有 Pgvector 配置一致");
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

    /**
     * 验证不支持的存储表名明确失败。
     * @return 无返回值
     */
    @Test
    void unsupportedTableFailsExplicitly() {
        runner.withPropertyValues("spring.ai.vectorstore.pgvector.table-name=other_vectors").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "当前迁移适配仅支持 public.vector_store 和 COSINE_DISTANCE");
        });
    }
}
