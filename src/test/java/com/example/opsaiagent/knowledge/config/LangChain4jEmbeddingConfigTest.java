package com.example.opsaiagent.knowledge.config;

import com.example.opsaiagent.ai.adapter.springai.LangChain4jEmbeddingBridge;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量模型的生产自动配置选择测试，不连接外部服务。 */
class LangChain4jEmbeddingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LangChain4jEmbeddingConfig.class)
            .withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
            .withPropertyValues("spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.embedding.api-key=test-embedding-key",
                    "spring.ai.vectorstore.pgvector.dimensions=3");

    /**
     * 验证默认配置仍使用 Spring AI 模型且不注册 LangChain4j 模型。
     * @return 无返回值
     */
    @Test
    void defaultProviderUsesSpringAi() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context.getBean(EmbeddingModel.class))
                    .isInstanceOf(org.springframework.ai.openai.OpenAiEmbeddingModel.class);
            assertThat(context).doesNotHaveBean(dev.langchain4j.model.embedding.EmbeddingModel.class);
        });
    }

    /**
     * 验证选择 LangChain4j 后向量存储优先注入过渡模型。
     * @return 无返回值
     */
    @Test
    void langChain4jProviderReplacesEmbeddingModel() {
        runner.withPropertyValues("ops-agent.ai.provider=langchain4j").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(dev.langchain4j.model.embedding.EmbeddingModel.class);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(LangChain4jEmbeddingBridge.class);
            assertThat(context.getBean(EmbeddingModel.class).dimensions()).isEqualTo(3);
        });
    }

    /**
     * 验证配置维度不一致时启动失败，防止新旧向量混用。
     * @return 无返回值
     */
    @Test
    void incompatibleDimensionsFailAtStartup() {
        runner.withPropertyValues("ops-agent.ai.provider=langchain4j", "ops-agent.ai.embedding.dimensions=4")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "向量模型维度必须与现有 Pgvector 配置一致");
                });
    }

    /**
     * 验证数据库依赖齐备时正式注册已迁移存储，默认分支不注册。
     * @return 无返回值
     */
    @Test
    void registersMigratedStoreOnlyForLangChain4j() {
        var databaseRunner = runner
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                        () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new);
        databaseRunner.run(context -> assertThat(context)
                .doesNotHaveBean(com.example.opsaiagent.retrieval.KnowledgeVectorStore.class));
        databaseRunner.withPropertyValues("ops-agent.ai.provider=langchain4j").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(com.example.opsaiagent.retrieval.KnowledgeVectorStore.class);
            assertThat(context.getBean(com.example.opsaiagent.retrieval.KnowledgeVectorStore.class))
                    .isInstanceOf(com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jPgVectorStore.class);
        });
    }

    /**
     * 验证自定义表名不会静默写入默认表。
     * @return 无返回值
     */
    @Test
    void unsupportedTableFailsExplicitly() {
        runner.withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                        () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withPropertyValues("ops-agent.ai.provider=langchain4j",
                        "spring.ai.vectorstore.pgvector.table-name=other_vectors")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "当前迁移适配仅支持 public.vector_store 和 COSINE_DISTANCE");
                });
    }
}
