package com.example.opsaiagent.knowledge;

import com.example.opsaiagent.ai.adapter.springai.SpringAiDocumentMapper;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.model.EmbeddingContentFormatter;
import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 对照存量向量输入，避免格式化变化导致新旧向量混用。 */
class EmbeddingContentFormatterTest {
    /**
     * 验证全部模式、空元数据、哈希碰撞和模板占位符均保留旧输入。
     * @return 无返回值
     */
    @Test
    void matchesLegacyDefaultFormatting() {
        var collisionMetadata = new HashMap<String, Object>();
        for (int i = 0; i < 40; i++) {
            collisionMetadata.put("key" + i, "value" + i);
        }
        collisionMetadata.put("Aa", "{content}");
        collisionMetadata.put("BB", "{value}");
        for (Map<String, Object> metadata : List.of(Map.<String, Object>of(),
                Map.<String, Object>of("tenantId", 2L, "source", "告警.md", "enabled", true), collisionMetadata)) {
            for (EmbeddingMetadataMode mode : EmbeddingMetadataMode.values()) {
                Document document = new Document("正文 {metadata_string}\n检查", metadata);
                assertThat(EmbeddingContentFormatter.format(document, mode)).isEqualTo(
                        SpringAiDocumentMapper.toSpring(document).getFormattedContent(MetadataMode.valueOf(mode.name())));
            }
        }
    }
}
