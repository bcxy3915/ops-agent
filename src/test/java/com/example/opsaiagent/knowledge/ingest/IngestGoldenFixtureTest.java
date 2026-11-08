package com.example.opsaiagent.knowledge.ingest;

import com.example.opsaiagent.knowledge.ingest.parser.MarkdownDocumentParserStrategy;
import com.example.opsaiagent.knowledge.ingest.splitter.TokenFallbackSplitter;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.model.EmbeddingContentFormatter;
import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定夹具来自迁移前实际输出，不需要旧框架作为测试依赖。 */
class IngestGoldenFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 读取明确记录来源的迁移夹具。
     * @return 旧实现的固定输入及输出
     * @throws Exception 夹具读取错误
     */
    private JsonNode fixture() throws Exception {
        try (var input = new ClassPathResource("migration/ingest-legacy-golden.json").getInputStream()) {
            return mapper.readTree(input);
        }
    }

    /**
     * 验证 Markdown 正文、标题、引用、代码与分类不漂移。
     * @return 无返回值
     * @throws Exception 夹具读取错误
     */
    @Test
    void markdownMatchesRecordedResults() throws Exception {
        var parser = new MarkdownDocumentParserStrategy();
        for (JsonNode sample : fixture().path("markdown")) {
            List<Document> actual = parser.parse(new ByteArrayResource(
                    sample.path("input").asText().getBytes(StandardCharsets.UTF_8)));
            assertChunks(actual, sample.path("chunks"));
        }
    }

    /**
     * 验证 token 分块正文、父块信息、权限元数据和评分。
     * @return 无返回值
     * @throws Exception 夹具读取错误
     */
    @Test
    void tokenWindowsMatchRecordedResults() throws Exception {
        for (JsonNode sample : fixture().path("token")) {
            var doc = new Document("parent-id", sample.path("input").asText(),
                    Map.of("tenantId", 2L, "source", "runbook.md"), 0.9);
            List<Document> actual = new TokenFallbackSplitter().split(List.of(doc), null);
            assertChunks(actual, sample.path("chunks"));
            assertThat(actual).allSatisfy(chunk -> assertThat(chunk.getScore()).isEqualTo(0.9));
            assertThat(doc.getMetadata()).doesNotContainKey("chunk_index");
        }
    }

    /**
     * 验证所有模式的格式与哈希碰撞时的元数据顺序。
     * @return 无返回值
     * @throws Exception 夹具读取错误
     */
    @Test
    void embeddingInputMatchesRecordedResults() throws Exception {
        for (JsonNode sample : fixture().path("format")) {
            Map<String, Object> metadata = mapper.convertValue(sample.path("metadata"),
                    new com.fasterxml.jackson.core.type.TypeReference<HashMap<String, Object>>() { });
            var doc = new Document(sample.path("input").asText(), metadata);
            // 夹具采集于 Windows，仅元数据行分隔符随运行平台转换。
            String expected = sample.path("expected").asText().replace("\r\n", System.lineSeparator());
            assertThat(EmbeddingContentFormatter.format(doc,
                    EmbeddingMetadataMode.valueOf(sample.path("mode").asText()))).isEqualTo(expected);
        }
    }

    /**
     * 验证读取失败显式报错。
     * @return 无返回值
     */
    @Test
    void reportsMarkdownReadFailure() {
        assertThatThrownBy(() -> new MarkdownDocumentParserStrategy()
                .parse(new ClassPathResource("missing-migration-file.md")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Markdown 文档解析失败");
    }

    /**
     * 对照正文与完整元数据，忽略随机生成的块 ID。
     * @param actual 当前分块
     * @param expected 固定结果
     * @return 无返回值
     * @throws Exception 元数据序列化错误
     */
    private void assertChunks(List<Document> actual, JsonNode expected) throws Exception {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < actual.size(); i++) {
            assertThat(actual.get(i).getText()).isEqualTo(expected.get(i).path("text").asText());
            assertThat(mapper.readTree(mapper.writeValueAsBytes(actual.get(i).getMetadata())))
                    .isEqualTo(expected.get(i).path("metadata"));
        }
    }
}
