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

    /**
     * 创建 LangChain4j 与 ES 存储适配器。
     * @param model 向量模型
     * @param es ES 客户端
     * @param projection MySQL 事务投影
     * @param dimensions 向量维度
     * @param metadataMode 嵌入元数据模式
     * @return 向量存储
     */
    @Bean
    public LangChain4jElasticsearchStore langChain4jKnowledgeVectorStore(
            EmbeddingModel model,
            ElasticsearchGateway es,
            SearchProjectionService projection,
            @Value("${ops-agent.ai.embedding.dimensions:1024}") int dimensions,
            @Value("${ops-agent.ai.embedding.metadata-mode:EMBED}") EmbeddingMetadataMode metadataMode) {
        return new LangChain4jElasticsearchStore(
                model, metadataMode, dimensions, es, projection);
    }

    /**
     * 创建专用于知识入库和检索的 LangChain4j 向量模型。
     * @param baseUrl 向量服务地址，独立于聊天服务
     * @param apiKey 向量服务密钥
     * @param modelName 现有向量模型名称
     * @param dimensions 向量模型维度
     * @param storeDimensions 现有 ES 表配置维度
     * @param timeout 模型请求超时
     * @param maxRetries 最大重试次数
     * @return LangChain4j 向量模型
     */
    @Bean
    public EmbeddingModel langChain4jEmbeddingModel(
            @Value("${ops-agent.ai.embedding.base-url:${spring.ai.openai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}}") String baseUrl,
            @Value("${ops-agent.ai.embedding.api-key:${spring.ai.openai.embedding.api-key:}}") String apiKey,
            @Value("${ops-agent.ai.embedding.model:${spring.ai.openai.embedding.model:text-embedding-v3}}") String modelName,
            @Value("${ops-agent.ai.embedding.dimensions:1024}") int dimensions,
            @Value("${ops-agent.ai.vector-store.dimensions:1024}") int storeDimensions,
            @Value("${ops-agent.ai.embedding.timeout:60s}") String timeout,
            @Value("${ops-agent.ai.embedding.max-retries:2}") int maxRetries) {
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
