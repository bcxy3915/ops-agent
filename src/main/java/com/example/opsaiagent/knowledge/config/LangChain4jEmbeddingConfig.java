package com.example.opsaiagent.knowledge.config;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jElasticsearchStore;
import com.example.opsaiagent.retrieval.elasticsearch.ElasticsearchGateway;
import com.example.opsaiagent.retrieval.elasticsearch.SearchProjectionService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识向量固定使用 LangChain4j 与 ES，与聊天 provider 无关。 */
@Configuration(proxyBeanMethods = false)
public class LangChain4jEmbeddingConfig {

    @Value("${ops-agent.ai.embedding.dimensions:1024}")
    private int dimensions;

    @Value("${ops-agent.ai.embedding.metadata-mode:EMBED}")
    private EmbeddingMetadataMode metadataMode;

    @Value("${ops-agent.ai.embedding.base-url:${spring.ai.openai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}}")
    private String baseUrl;

    @Value("${ops-agent.ai.embedding.api-key:${spring.ai.openai.embedding.api-key:}}")
    private String apiKey;

    @Value("${ops-agent.ai.embedding.model:${spring.ai.openai.embedding.model:text-embedding-v3}}")
    private String modelName;

    @Value("${ops-agent.ai.vector-store.dimensions:1024}")
    private int storeDimensions;

    @Value("${ops-agent.ai.embedding.timeout:60s}")
    private String timeout;

    @Value("${ops-agent.ai.embedding.max-retries:2}")
    private int maxRetries;

    /**
     * 创建 LangChain4j 与 ES 存储适配器。
     * @param model 向量模型
     * @param es ES 客户端
     * @param projection MySQL 事务投影
     * @return 向量存储
     */
    @Bean
    public LangChain4jElasticsearchStore langChain4jKnowledgeVectorStore(
            EmbeddingModel model,
            ElasticsearchGateway es,
            SearchProjectionService projection) {
        return new LangChain4jElasticsearchStore(
                model, metadataMode, dimensions, es, projection);
    }

    /**
     * 创建专用于知识入库和检索的 LangChain4j 向量模型。
     * 无入参。
     * @return LangChain4j 向量模型
     */
    @Bean
    public EmbeddingModel langChain4jEmbeddingModel() {
        if (dimensions <= 0 || dimensions != storeDimensions) {
            throw new IllegalArgumentException("向量模型维度必须与现有 ES 配置一致");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("向量模型重试次数不能为负数");
        }
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                .dimensions(dimensions).timeout(DurationStyle.detectAndParse(timeout))
                .maxRetries(maxRetries).maxSegmentsPerBatch(10)
                .build();
    }

}
