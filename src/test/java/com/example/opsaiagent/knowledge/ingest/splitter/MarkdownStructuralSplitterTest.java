package com.example.opsaiagent.knowledge.ingest.splitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.example.opsaiagent.knowledge.model.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown 结构化切分单测：标题栈 headingPath 生成、H4 不入路径、
 * 超长 section 委托递归切分（子块继承 section metadata）、
 * 围栏内 # 行不识别为标题、无标题无 section metadata、metadata 透传。
 */
@DisplayName("Markdown 结构化切分")
class MarkdownStructuralSplitterTest {

    private final TokenMeter tokenMeter = new TokenMeter();

    private MarkdownStructuralSplitter splitter;

    private final SplitOptions options = new SplitOptions(120, 30, 30, 240, true, true);

    @BeforeEach
    void setUp() {
        splitter = new MarkdownStructuralSplitter(
                new RecursiveBoundarySplitter(tokenMeter), tokenMeter, SplitterTestFixture.properties());
    }

    // ==================== 标题栈与 headingPath ====================

    @Test
    @DisplayName("headingPath 按标题层级生成，同级回退、跨级回退均正确")
    void scanSections_headingPathHierarchy() {
        String md = """
                # 服务手册
                手册概述。
                ## 数据库
                数据库概述。
                ### 主从切换
                切换步骤说明。
                ### 备份恢复
                备份策略说明。
                ## 中间件
                中间件概述。
                """;

        List<MarkdownStructuralSplitter.Section> sections = splitter.scanSections(md);

        assertThat(sections).extracting(MarkdownStructuralSplitter.Section::headingPath)
                .containsExactly(
                        "服务手册",
                        "服务手册 > 数据库",
                        "服务手册 > 数据库 > 主从切换",
                        "服务手册 > 数据库 > 备份恢复",
                        "服务手册 > 中间件");
        // section 原文包含标题行自身
        assertThat(sections.get(2).text()).startsWith("### 主从切换");
        assertThat(sections.get(3).text()).startsWith("### 备份恢复");
    }

    @Test
    @DisplayName("首标题之前的 preamble 形成 headingPath 为空的 section")
    void scanSections_preambleBeforeFirstHeading() {
        String md = "前言说明。\n# 标题\n正文。\n";

        List<MarkdownStructuralSplitter.Section> sections = splitter.scanSections(md);

        assertThat(sections).extracting(MarkdownStructuralSplitter.Section::headingPath)
                .containsExactly("", "标题");
        assertThat(sections.get(0).text()).isEqualTo("前言说明。\n");
    }

    @Test
    @DisplayName("超过 maxHeadingLevel 的标题不入路径，但仍是 section 边界")
    void scanSections_h4NotInPath() {
        String md = "# 一级\n## 二级\n### 三级\n#### 四级\n四级内容。\n";

        List<MarkdownStructuralSplitter.Section> sections = splitter.scanSections(md);

        assertThat(sections).extracting(MarkdownStructuralSplitter.Section::headingPath)
                .containsExactly("一级", "一级 > 二级", "一级 > 二级 > 三级", "一级 > 二级 > 三级");
        assertThat(sections.get(3).text()).startsWith("#### 四级");
    }

    @Test
    @DisplayName("围栏代码块内的 # 行不识别为标题")
    void scanSections_fenceContentNotHeading() {
        String md = "# 真标题\n正文开始。\n```java\n# 代码里的假标题\nString s = \"x\";\n```\n正文结束。\n";

        List<MarkdownStructuralSplitter.Section> sections = splitter.scanSections(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).headingPath()).isEqualTo("真标题");
        assertThat(sections.get(0).text()).contains("# 代码里的假标题");
    }

    // ==================== split：块与 metadata ====================

    @Test
    @DisplayName("短 section 整块输出并携带 section metadata；preamble 无 section metadata")
    void split_shortSectionsCarrySectionMetadata() {
        String md = "前言说明。\n# 标题\n正文内容。\n";

        List<Document> chunks = splitter.split(List.of(new Document(md)), options);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getMetadata()).doesNotContainKey(MarkdownStructuralSplitter.METADATA_SECTION);
        assertThat(chunks.get(1).getMetadata())
                .containsEntry(MarkdownStructuralSplitter.METADATA_SECTION, "标题");
        assertThat(chunks.get(0).getText()).isEqualTo("前言说明。\n");
        assertThat(chunks.get(1).getText()).isEqualTo("# 标题\n正文内容。\n");
    }

    @Test
    @DisplayName("超长 section 委托递归切分，子块全部继承 section metadata 且不超硬上限")
    void split_longSectionDelegatesToRecursive() {
        String md = "# 手册\n## 数据库\n" + sentences(1, 40) + "\n";

        List<Document> chunks = splitter.split(List.of(new Document(md)), options);

        assertThat(chunks).hasSizeGreaterThan(1);
        // 无正文的 H1 自成一个空章节块
        assertThat(chunks.get(0).getMetadata())
                .containsEntry(MarkdownStructuralSplitter.METADATA_SECTION, "手册");
        assertThat(chunks.get(0).getText()).isEqualTo("# 手册\n");
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getMetadata())
                    .containsEntry(MarkdownStructuralSplitter.METADATA_SECTION, "手册 > 数据库");
            assertThat(tokenMeter.count(chunks.get(i).getText()))
                    .isLessThanOrEqualTo(options.maxChunkTokens());
        }
        assertThat(chunks.get(1).getText()).startsWith("## 数据库");
    }

    @Test
    @DisplayName("无任何标题的 Markdown 不失败、不产出 section metadata")
    void split_noHeadingsStillSplits() {
        String md = sentences(1, 10);

        List<Document> chunks = splitter.split(List.of(new Document(md)), options);

        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            assertThat(chunk.getMetadata()).doesNotContainKey(MarkdownStructuralSplitter.METADATA_SECTION);
        }
    }

    @Test
    @DisplayName("输出块携带输入文档全部 metadata")
    void split_metadataPropagated() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "runbook.md");
        metadata.put("tenantId", "0");

        List<Document> chunks = splitter.split(
                List.of(new Document("# 标题\n" + sentences(1, 10), metadata)), options);

        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            assertThat(chunk.getMetadata())
                    .containsEntry("source", "runbook.md")
                    .containsEntry("tenantId", "0")
                    .containsEntry(MarkdownStructuralSplitter.METADATA_SECTION, "标题");
        }
    }

    @Test
    @DisplayName("空白文档被跳过")
    void split_blankDocSkipped() {
        assertThat(splitter.split(List.of(new Document("  \n\n ")), options)).isEmpty();
    }

    // ==================== 测试助手 ====================

    private static String sentences(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append("这是第").append(i).append("句话，描述数据库巡检的具体执行步骤与验证方式，内容保持完整。");
        }
        return sb.toString();
    }
}
