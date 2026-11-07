package com.example.opsaiagent.ai.adapter.springai;

import com.example.opsaiagent.knowledge.model.Document;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证框架边界转换不会丢失文档身份、评分和权限元数据。 */
class SpringAiDocumentMapperTest {

    /**
     * 验证往返转换保留检索属性。
     * @return 无返回值
     */
    @Test
    void roundTripPreservesRetrievalAttributes() {
        Document document = new Document("chunk-1", "处理步骤",
                Map.of("tenantId", 7L, "visibility", "TENANT", "source", "runbook.md"), 0.87);

        assertThat(SpringAiDocumentMapper.fromSpring(SpringAiDocumentMapper.toSpring(document)))
                .isEqualTo(document);
    }

    /**
     * 验证融合阶段修改元数据不会污染输入和原框架文档。
     * @return 无返回值
     */
    @Test
    void metadataChangesAreIsolatedFromInput() {
        Map<String, Object> metadata = new HashMap<>(Map.of("source", "runbook.md"));
        Document document = new Document("步骤", metadata);
        document.getMetadata().put("score", 0.9);
        assertThat(metadata).doesNotContainKey("score");

        var springDocument = SpringAiDocumentMapper.toSpring(document);
        Document converted = SpringAiDocumentMapper.fromSpring(springDocument);
        converted.getMetadata().put("rank", 1);
        assertThat(springDocument.getMetadata()).doesNotContainKey("rank");
    }

    /**
     * 验证无评分文档和空列表可经回退边界转换。
     * @return 无返回值
     */
    @Test
    void unscoredDocumentsAndEmptyListsRemainCompatible() {
        Document document = new Document("chunk-2", "正文", null);
        List<Document> result = SpringAiDocumentMapper.fromSpring(
                SpringAiDocumentMapper.toSpring(List.of(document)));

        assertThat(result).containsExactly(document);
        assertThat(result.getFirst().getMetadata()).isEmpty();
        assertThat(result.getFirst().getScore()).isNull();
        assertThat(SpringAiDocumentMapper.fromSpring(List.of())).isEmpty();
        assertThat(SpringAiDocumentMapper.toSpring(List.of())).isEmpty();
    }
}
