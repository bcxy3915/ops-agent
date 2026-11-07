package com.example.opsaiagent.ai.adapter.springai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LangChain4j 向量模型与现有存储之间的兼容性测试。 */
class LangChain4jEmbeddingBridgeTest {

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final LangChain4jEmbeddingBridge bridge =
            new LangChain4jEmbeddingBridge(model, "test-embedding", 3, MetadataMode.EMBED);

    /**
     * 验证批量向量的数量、顺序、模型标识和用量均保留。
     * @return 无返回值
     */
    @Test
    void preservesBatchOrderAndUsage() {
        when(model.embedAll(List.of(TextSegment.from("第一段"), TextSegment.from("第二段"))))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{1, 0, 0}),
                        Embedding.from(new float[]{0, 1, 0})), new TokenUsage(8, 0, 8)));
        var result = bridge.call(new EmbeddingRequest(List.of("第一段", "第二段"), null));
        assertThat(result.getResults()).extracting(org.springframework.ai.embedding.Embedding::getIndex)
                .containsExactly(0, 1);
        assertThat(result.getResults().get(0).getOutput()).containsExactly(1, 0, 0);
        assertThat(result.getResults().get(1).getOutput()).containsExactly(0, 1, 0);
        assertThat(result.getMetadata().getModel()).isEqualTo("test-embedding");
        assertThat(result.getMetadata().getUsage().getPromptTokens()).isEqualTo(8);
    }

    /**
     * 验证维度探测与空批次不会访问外部模型。
     * @return 无返回值
     */
    @Test
    void dimensionsAndEmptyBatchDoNotCallModel() {
        assertThat(bridge.dimensions()).isEqualTo(3);
        assertThat(bridge.call(new EmbeddingRequest(List.of(), null)).getResults()).isEmpty();
        verifyNoInteractions(model);
    }

    /**
     * 验证单文档与批量入库使用相同内容格式化规则。
     * @return 无返回值
     */
    @Test
    void preservesDocumentFormatting() {
        Document doc = new Document("处理步骤", Map.of("source", "runbook.md"));
        String formatted = doc.getFormattedContent(MetadataMode.EMBED);
        when(model.embedAll(List.of(TextSegment.from(formatted))))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{1, 0, 0}))));
        assertThat(bridge.getEmbeddingContent(doc)).isEqualTo(formatted);
        assertThat(bridge.embed(doc)).containsExactly(1, 0, 0);
        verify(model).embedAll(List.of(TextSegment.from(formatted)));
    }

    /**
     * 验证异常维度、数量与非有限数值被拒绝，不进入存储。
     * @return 无返回值
     */
    @Test
    void rejectsInvalidModelResults() {
        when(model.embedAll(anyList()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{1, 0}))))
                .thenReturn(Response.from(List.of()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{Float.NaN, 0, 1}))));
        var request = new EmbeddingRequest(List.of("查询"), null);
        assertThatThrownBy(() -> bridge.call(request)).hasMessageContaining("维度");
        assertThatThrownBy(() -> bridge.call(request)).hasMessageContaining("数量");
        assertThatThrownBy(() -> bridge.call(request)).hasMessageContaining("非有限数值");
    }

    /**
     * 验证请求不能改变存量向量库的模型或维度。
     * @return 无返回值
     */
    @Test
    void rejectsPerRequestModelAndDimensionChanges() {
        assertThatThrownBy(() -> bridge.call(new EmbeddingRequest(List.of("查询"),
                EmbeddingOptions.builder().model("other-model").build())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.call(new EmbeddingRequest(List.of("查询"),
                EmbeddingOptions.builder().dimensions(2).build())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(model);
    }

    /**
     * 验证模型异常向上传播，交给现有索引重试与 BM25 降级处理。
     * @return 无返回值
     */
    @Test
    void propagatesModelFailure() {
        when(model.embedAll(anyList())).thenThrow(new IllegalStateException("模型不可用"));
        assertThatThrownBy(() -> bridge.embed("查询")).hasMessage("模型不可用");
    }
}
