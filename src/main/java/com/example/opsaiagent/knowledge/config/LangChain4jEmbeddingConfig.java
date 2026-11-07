package com.example.opsaiagent.knowledge.config;

import com.example.opsaiagent.ai.adapter.springai.LangChain4jEmbeddingBridge;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 将知识向量模型调用切换至 LangChain4j，兼容现有 Pgvector 存储。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ops-agent.ai", name = "provider", havingValue = "langchain4j")
public class LangChain4jEmbeddingConfig {

    /**
     * 注册直接使用 JDBC 的 LangChain4j 向量存储。
     * @param jdbc 数据库客户端
     * @param model LangChain4j 向量模型
     * @param mapper JSON 映射器
     * @param dimensions 向量维度
     * @param metadataMode 文档格式化方式
     * @param schemaName 现有向量表所在 schema
     * @param tableName 现有向量表名
     * @param distanceType 现有距离算法
     * @return 知识向量存储
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(org.springframework.jdbc.core.JdbcTemplate.class)
    public com.example.opsaiagent.retrieval.KnowledgeVectorStore langChain4jKnowledgeVectorStore(
            org.springframework.jdbc.core.JdbcTemplate jdbc, EmbeddingModel model,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            @Value("${ops-agent.ai.embedding.dimensions:${spring.ai.vectorstore.pgvector.dimensions:1024}}") int dimensions,
            @Value("${spring.ai.openai.embedding.metadata-mode:EMBED}") MetadataMode metadataMode,
            @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName,
            @Value("${spring.ai.vectorstore.pgvector.distance-type:COSINE_DISTANCE}") String distanceType) {
        if (!"public".equals(schemaName) || !"vector_store".equals(tableName)
                || !"COSINE_DISTANCE".equals(distanceType)) {
            throw new IllegalArgumentException("当前迁移适配仅支持 public.vector_store 和 COSINE_DISTANCE");
        }
        return new com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jPgVectorStore(
                jdbc, model, mapper, dimensions, metadataMode);
    }

    /**
     * 创建专用于知识入库和检索的 LangChain4j 向量模型。
     * @param baseUrl 向量服务地址，独立于聊天服务
     * @param apiKey 向量服务密钥
     * @param modelName 现有向量模型名称
     * @param dimensions 向量模型维度
     * @param storeDimensions 现有 Pgvector 表配置维度
     * @param timeout 模型请求超时
     * @param maxRetries 最大重试次数
     * @return LangChain4j 向量模型
     */
    @Bean
    public EmbeddingModel langChain4jEmbeddingModel(
            @Value("${ops-agent.ai.embedding.base-url:${spring.ai.openai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}}") String baseUrl,
            @Value("${ops-agent.ai.embedding.api-key:${spring.ai.openai.embedding.api-key:}}") String apiKey,
            @Value("${ops-agent.ai.embedding.model:${spring.ai.openai.embedding.model:text-embedding-v3}}") String modelName,
            @Value("${ops-agent.ai.embedding.dimensions:${spring.ai.vectorstore.pgvector.dimensions:1024}}") int dimensions,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int storeDimensions,
            @Value("${ops-agent.ai.embedding.timeout:60s}") String timeout,
            @Value("${ops-agent.ai.embedding.max-retries:2}") int maxRetries) {
        if (dimensions <= 0 || dimensions != storeDimensions) {
            throw new IllegalArgumentException("向量模型维度必须与现有 Pgvector 配置一致");
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

    /**
     * 将 LangChain4j 模型接入当前 Pgvector 存储的模型接口。
     * @param model LangChain4j 向量模型
     * @param modelName 模型名称
     * @param dimensions 向量维度
     * @param metadataMode 原有文档格式化规则
     * @return 现有存储使用的向量模型适配层
     */
    @Bean
    @Primary
    public org.springframework.ai.embedding.EmbeddingModel langChain4jEmbeddingBridge(
            EmbeddingModel model,
            @Value("${ops-agent.ai.embedding.model:${spring.ai.openai.embedding.model:text-embedding-v3}}") String modelName,
            @Value("${ops-agent.ai.embedding.dimensions:${spring.ai.vectorstore.pgvector.dimensions:1024}}") int dimensions,
            @Value("${spring.ai.openai.embedding.metadata-mode:EMBED}") MetadataMode metadataMode) {
        return new LangChain4jEmbeddingBridge(model, modelName, dimensions, metadataMode);
    }
}
