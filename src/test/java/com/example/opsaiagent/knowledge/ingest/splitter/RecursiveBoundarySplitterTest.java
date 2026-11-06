package com.example.opsaiagent.knowledge.ingest.splitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 递归边界切分单测：围绕 TextChunkStrategy 声明的四条不变量设计
 * （不丢内容 / 不产空块 / 不超 maxChunkTokens / metadata 透传），
 * 并覆盖围栏、表格原子保护、尾块合并、重叠前缀与病态长句硬切。
 *
 * 使用小预算（chunk=120 / overlap=30 / max=240）降低用例体积，逻辑与生产配置一致。
 */
@DisplayName("递归边界切分")
class RecursiveBoundarySplitterTest {

    private final TokenMeter tokenMeter = new TokenMeter();

    private RecursiveBoundarySplitter splitter;

    /** 小预算：约 3 句成块，便于触发多块 / 重叠 / 尾块合并路径 */
    private final SplitOptions options = new SplitOptions(120, 30, 30, 240, true, true);

    @BeforeEach
    void setUp() {
        splitter = new RecursiveBoundarySplitter(tokenMeter);
    }

    // ==================== 句子与行切分基础 ====================

    @Test
    @DisplayName("splitSentences：句终符号后、空白前断开，空白保留下一句头部")
    void splitSentences_basic() {
        List<String> sentences = RecursiveBoundarySplitter.splitSentences("第一句。\n第二句！结尾无空白");

        assertThat(sentences).containsExactly("第一句。", "\n第二句！结尾无空白");
    }

    @Test
    @DisplayName("splitSentences：冒号不作为切分点")
    void splitSentences_colonNotBoundary() {
        List<String> sentences = RecursiveBoundarySplitter.splitSentences("配置项：值说明。第二项：值。");

        assertThat(sentences).hasSize(1);
        assertThat(sentences.get(0)).isEqualTo("配置项：值说明。第二项：值。");
    }

    @Test
    @DisplayName("splitKeepBreaks：按行切分且保留行尾换行")
    void splitKeepBreaks_keepsBreaks() {
        assertThat(RecursiveBoundarySplitter.splitKeepBreaks("a\nb"))
                .containsExactly("a\n", "b");
        assertThat(String.join("", RecursiveBoundarySplitter.splitKeepBreaks("a\nb\n\n c")))
                .isEqualTo("a\nb\n\n c");
    }

    // ==================== 不变量：短文本单块 ====================

    @Test
    @DisplayName("短文本切分为单块且内容不变")
    void split_shortTextSingleChunk() {
        List<Document> chunks = splitter.split(List.of(new Document("只有一句话的短文本。")), options);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo("只有一句话的短文本。");
    }

    @Test
    @DisplayName("空白文档被跳过，不产出空白块")
    void split_blankDocSkipped() {
        assertThat(splitter.split(List.of(new Document("  \n\n  ")), options)).isEmpty();
    }

    // ==================== 不变量：中文长段多块 ====================

    @Test
    @DisplayName("中文长段：多块、无空块、每块不超硬上限、拼接与原文一致")
    void splitText_longChineseParagraph() {
        String text = sentences(1, 40);

        List<String> blocks = splitter.splitText(text, options);

        assertThat(blocks).hasSizeGreaterThan(1);
        for (String block : blocks) {
            assertThat(block.strip()).isNotEmpty();
            assertThat(tokenMeter.count(block)).isLessThanOrEqualTo(options.maxChunkTokens());
        }
        assertThat(reconstruct(blocks)).isEqualTo(text);
    }

    // ==================== 不变量：重叠前缀 ====================

    @Test
    @DisplayName("相邻块携带整句重叠：后块包含前块最后一句，且拼接仍无损")
    void splitText_overlapCarriesWholeSentences() {
        String text = shortSentences(1, 50);

        List<String> blocks = splitter.splitText(text, options);

        assertThat(blocks).hasSizeGreaterThan(1);
        for (int i = 1; i < blocks.size(); i++) {
            String prev = blocks.get(i - 1);
            String lastSentence = prev.substring(prev.lastIndexOf("巡检步骤"));
            // 重叠从上一块尾部整句取得，后块必须包含该句
            assertThat(blocks.get(i)).contains(lastSentence);
        }
        assertThat(reconstruct(blocks)).isEqualTo(text);
    }

    // ==================== 病态长句硬切 ====================

    @Test
    @DisplayName("无边界病态长句：按 token 比例硬切，纯子串拼接无损")
    void splitText_hardSplitNoBoundary() {
        String text = "数据库巡检任务执行中".repeat(120);

        List<String> blocks = splitter.splitText(text, options);

        assertThat(blocks).hasSizeGreaterThan(1);
        for (String block : blocks) {
            assertThat(tokenMeter.count(block)).isLessThanOrEqualTo(options.maxChunkTokens());
        }
        // 无句终符号时重叠无法取整句，退化为纯子串切分，直接拼接即原文
        assertThat(String.join("", blocks)).isEqualTo(text);
    }

    // ==================== 围栏代码块原子保护 ====================

    @Test
    @DisplayName("围栏代码块作为原子单元，不与任何块边界相交")
    void splitText_fenceIsAtomic() {
        String text = sentences(1, 5) + "\n```bash\nsystemctl restart mysqld\necho done\n```\n" + sentences(6, 10);

        List<String> blocks = splitter.splitText(text, options);

        String fenceBlock = blocks.stream()
                .filter(b -> b.contains("systemctl restart mysqld"))
                .findFirst()
                .orElseThrow();
        // 开围栏、内部命令、闭围栏必须同块
        assertThat(fenceBlock).contains("```bash");
        assertThat(fenceBlock).contains("\n```\n");
        assertThat(reconstruct(blocks)).isEqualTo(text);
    }

    @Test
    @DisplayName("波浪线围栏同样受原子保护")
    void splitText_tildeFenceIsAtomic() {
        String text = sentences(1, 5) + "\n~~~yaml\nport: 5432\n~~~\n" + sentences(6, 10);

        List<String> blocks = splitter.splitText(text, options);

        String fenceBlock = blocks.stream()
                .filter(b -> b.contains("port: 5432"))
                .findFirst()
                .orElseThrow();
        assertThat(fenceBlock).contains("~~~yaml");
        assertThat(fenceBlock).contains("\n~~~\n");
    }

    // ==================== 表格原子保护 ====================

    @Test
    @DisplayName("连续表格行作为原子单元，不拆行")
    void splitText_tableRowsAreAtomic() {
        String text = sentences(1, 4)
                + "\n| 指标 | 阈值 |\n| CPU 使用率 | 80% |\n| 内存占用 | 90% |\n"
                + sentences(5, 8);

        List<String> blocks = splitter.splitText(text, options);

        String tableBlock = blocks.stream()
                .filter(b -> b.contains("| CPU 使用率 |"))
                .findFirst()
                .orElseThrow();
        assertThat(tableBlock).contains("| 指标 | 阈值 |");
        assertThat(tableBlock).contains("| 内存占用 |");
        assertThat(reconstruct(blocks)).isEqualTo(text);
    }

    // ==================== 尾块合并 ====================

    @Test
    @DisplayName("尾块不足 minChunkChars 时并入前一块")
    void splitText_tinyTailMerged() {
        // 单句约 130+ token（超过 120 预算但低于 240 硬上限），尾句"收尾。"成独立尾块后被合并
        String text = "无标点超长巡检语句占位".repeat(13) + "。\n收尾。";

        List<String> blocks = splitter.splitText(text, options);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).endsWith("收尾。");
    }

    // ==================== metadata 透传 ====================

    @Test
    @DisplayName("输出块携带输入文档全部 metadata")
    void split_metadataPropagated() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "runbook.txt");
        metadata.put("tenantId", "0");

        List<Document> chunks = splitter.split(List.of(new Document(sentences(1, 40), metadata)), options);

        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            assertThat(chunk.getMetadata())
                    .containsEntry("source", "runbook.txt")
                    .containsEntry("tenantId", "0");
        }
    }

    // ==================== 测试助手 ====================

    /** 生成 [from, to] 编号的中文句子，句间以换行分隔（无尾随换行，避免末尾孤立空白单元） */
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

    /** 生成 [from, to] 编号的短句（约 12 token/句，低于重叠预算 30，用于验证重叠取整句） */
    private static String shortSentences(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append("巡检步骤").append(i).append("已执行完毕。");
        }
        return sb.toString();
    }

    /** 从当前块头部剥离与上一块尾部最长重叠前缀（不允许整块重复） */
    private static String stripOverlap(String prev, String current) {
        int max = Math.min(prev.length() - 1, current.length());
        for (int len = max; len > 0; len--) {
            if (prev.regionMatches(prev.length() - len, current, 0, len)) {
                return current.substring(len);
            }
        }
        return current;
    }

    /** 块按序拼接（剥离相邻重叠）后应与原文完全一致——「不丢内容」不变量 */
    private static String reconstruct(List<String> blocks) {
        if (blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(blocks.get(0));
        for (int i = 1; i < blocks.size(); i++) {
            sb.append(stripOverlap(blocks.get(i - 1), blocks.get(i)));
        }
        return sb.toString();
    }
}
