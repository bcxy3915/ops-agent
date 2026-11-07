package com.example.opsaiagent.knowledge.ingest.splitter;

import com.example.opsaiagent.ai.adapter.springai.SpringAiDocumentMapper;
import com.example.opsaiagent.knowledge.model.Document;
import org.junit.jupiter.api.Test;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证固定 token 兜底规则与旧实现一致。 */
class TokenFallbackMigrationTest {
    /**
     * 对照短文本、中英文长文本、边界窗口及分块元数据。
     * @return 无返回值
     */
    @Test
    void matchesLegacyWindowsAndMetadata() {
        var legacy = TokenTextSplitter.builder().withChunkSize(500).withMinChunkSizeChars(200)
                .withMaxNumChunks(100_000).withKeepSeparator(true).build();
        var splitter = new TokenFallbackSplitter();
        for (String text : List.of("", "短文", "  服务检查正常，继续处理。  ",
                "CPU usage is high. Check service health!\n".repeat(140),
                "数据库连接池耗尽，需要查询慢日志。\n".repeat(130), " a".repeat(500), " a".repeat(501))) {
            var document = new Document("parent-id", text, Map.of("tenantId", 2L, "source", "runbook.md"), 0.9);
            var expected = legacy.apply(SpringAiDocumentMapper.toSpring(List.of(document)));
            var actual = splitter.split(List.of(document), null);
            assertThat(actual).hasSize(expected.size());
            for (int i = 0; i < actual.size(); i++) {
                assertThat(actual.get(i).getText()).isEqualTo(expected.get(i).getText());
                assertThat(actual.get(i).getMetadata()).isEqualTo(expected.get(i).getMetadata());
                assertThat(actual.get(i).getScore()).isEqualTo(0.9);
            }
            assertThat(document.getMetadata()).doesNotContainKey("chunk_index");
        }
    }
}
