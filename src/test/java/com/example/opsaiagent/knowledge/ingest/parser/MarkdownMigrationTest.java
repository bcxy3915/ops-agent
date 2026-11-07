package com.example.opsaiagent.knowledge.ingest.parser;

import org.junit.jupiter.api.Test;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证新解析器与迁移前配置的文本及元数据契约。 */
class MarkdownMigrationTest {
    /**
     * 对照标题、引用、链接、代码、列表及换行处理。
     * @return 无返回值
     */
    @Test
    void matchesLegacyExtraction() {
        var parser = new MarkdownDocumentParserStrategy();
        var config = MarkdownDocumentReaderConfig.builder().withHorizontalRuleCreateDocument(false)
                .withIncludeCodeBlock(true).withIncludeBlockquote(true).build();
        for (String text : List.of("", "# 健康检查\n\n服务正常\n下一行  \n硬换行\n\n## 指标\n查询 **CPU** 和 [文档](https://example.com)",
                "# 排查\n\n> 检查 `threads`\n\n- CPU\n- 内存\n\n```shell\ncurl localhost/health\n```\n\n后续步骤\n\n---\n\n结束",
                "标题\n===\n\n正文\n\n## **强调标题**\n\n内容\n\n![说明](image.png)")) {
            var resource = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
            var expected = new MarkdownDocumentReader(resource, config).get();
            var actual = parser.parse(resource);
            assertThat(actual).hasSize(expected.size());
            for (int i = 0; i < actual.size(); i++) {
                assertThat(actual.get(i).getText()).isEqualTo(expected.get(i).getText());
                assertThat(actual.get(i).getMetadata()).isEqualTo(expected.get(i).getMetadata());
            }
        }
    }

    /**
     * 验证资源读取失败不会被伪装成空文档。
     * @return 无返回值
     */
    @Test
    void reportsReadFailure() {
        assertThatThrownBy(() -> new MarkdownDocumentParserStrategy()
                .parse(new org.springframework.core.io.ClassPathResource("missing-migration-file.md")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Markdown 文档解析失败");
    }
}
