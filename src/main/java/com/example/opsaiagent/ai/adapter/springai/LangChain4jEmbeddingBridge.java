package com.example.opsaiagent.ai.adapter.springai;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 让现有 Pgvector 存储通过 LangChain4j 生成向量；存储接口移除后删除此过渡适配层。 */
public final class LangChain4jEmbeddingBridge implements org.springframework.ai.embedding.EmbeddingModel {

    private final EmbeddingModel model;
    private final String modelName;
    private final int dimensions;
    private final MetadataMode metadataMode;

    /**
     * 创建兼容现有向量存储的模型适配层。
     * @param model LangChain4j 向量模型
     * @param modelName 模型名称
     * @param dimensions 已有向量表维度
     * @param metadataMode 原有文档内容格式化方式
     * @return 无返回值
     */
    public LangChain4jEmbeddingBridge(EmbeddingModel model, String modelName, int dimensions,
                                     MetadataMode metadataMode) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("向量维度必须大于零");
        }
        this.model = Objects.requireNonNull(model);
        this.modelName = Objects.requireNonNull(modelName);
        this.dimensions = dimensions;
        this.metadataMode = Objects.requireNonNull(metadataMode);
    }

    /**
     * 将存储侧批量请求交给 LangChain4j，校验结果后按输入顺序回填。
     * @param request 向量请求
     * @return 带有向量、顺序和用量的响应
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Objects.requireNonNull(request, "向量请求不能为空");
        var options = request.getOptions();
        if (options != null && ((options.getModel() != null && !modelName.equals(options.getModel()))
                || (options.getDimensions() != null && dimensions != options.getDimensions()))) {
            throw new IllegalArgumentException("请求模型或维度与现有向量库配置不一致");
        }
        List<String> texts = Objects.requireNonNull(request.getInstructions(), "向量文本不能为空");
        if (texts.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }
        var response = model.embedAll(texts.stream().map(TextSegment::from).toList());
        var vectors = response.content();
        if (vectors == null || vectors.size() != texts.size()) {
            throw new IllegalStateException("向量模型返回数量与输入文本数量不一致");
        }
        List<Embedding> results = new ArrayList<>(vectors.size());
        for (int index = 0; index < vectors.size(); index++) {
            var vector = vectors.get(index);
            if (vector == null || vector.dimension() != dimensions) {
                throw new IllegalStateException("向量模型返回维度与现有向量库不一致");
            }
            for (float value : vector.vector()) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("向量模型返回非有限数值");
                }
            }
            results.add(new Embedding(vector.vector(), index));
        }
        var usage = response.tokenUsage();
        var metadata = new EmbeddingResponseMetadata();
        metadata.setModel(modelName);
        if (usage != null) {
            metadata.setUsage(new DefaultUsage(usage.inputTokenCount(), usage.outputTokenCount(),
                    usage.totalTokenCount()));
        }
        return new EmbeddingResponse(results, metadata);
    }

    /**
     * 保留原有文档及 metadata 的格式化语义。
     * @param document 待向量化的文档
     * @return 实际发送给模型的文本
     */
    @Override
    public String getEmbeddingContent(Document document) {
        return document.getFormattedContent(metadataMode);
    }

    /**
     * 使用相同内容规则向量化单个文档。
     * @param document 待向量化的文档
     * @return 文档向量
     */
    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    /**
     * 返回配置维度，避免存储初始化为探测维度而调用外部模型。
     * @return 已校验的向量维度
     */
    @Override
    public int dimensions() {
        return dimensions;
    }
}
