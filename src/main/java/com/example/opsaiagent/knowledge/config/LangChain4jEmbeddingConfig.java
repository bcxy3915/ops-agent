package com.example.opsaiagent.knowledge.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识向量固定使用 LangChain4j 与 JDBC，与聊天 provider 无关。 */
@Configuration(proxyBeanMethods = false)
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
    public com.example.opsaiagent.retrieval.KnowledgeVectorStore langChain4jKnowledgeVectorStore(
            org.springframework.jdbc.core.JdbcTemplate jdbc, EmbeddingModel model,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            @Value("${ops-agent.ai.embedding.dimensions:${spring.ai.vectorstore.pgvector.dimensions:1024}}") int dimensions,
            @Value("${ops-agent.ai.embedding.metadata-mode:${spring.ai.openai.embedding.metadata-mode:EMBED}}") EmbeddingMetadataMode metadataMode,
            @Value("${ops-agent.ai.vector-store.schema:${spring.ai.vectorstore.pgvector.schema-name:public}}") String schemaName,
            @Value("${ops-agent.ai.vector-store.table:${spring.ai.vectorstore.pgvector.table-name:vector_store}}") String tableName,
            @Value("${ops-agent.ai.vector-store.distance-type:${spring.ai.vectorstore.pgvector.distance-type:COSINE_DISTANCE}}") String distanceType) {
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
            @Value("${ops-agent.ai.vector-store.dimensions:${spring.ai.vectorstore.pgvector.dimensions:1024}}") int storeDimensions,
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

}
