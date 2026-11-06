package com.example.opsaiagent.knowledge.ingest.splitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策略路由器单测：四级路由（override → docType 配置 → supports → 默认）、
 * 配置回退旧口径（default-strategy / markdown.strategy 设为 token）、
 * 策略异常降级 token 兜底、splitStrategy / splitVersion 打标。
 */
@DisplayName("切分策略路由")
class TextChunkStrategyResolverTest {

    private SplitProperties properties;

    private MarkdownStructuralSplitter markdownStrategy;

    private RecursiveBoundarySplitter recursiveStrategy;

    private TokenFallbackSplitter tokenStrategy;

    private TextChunkStrategyResolver resolver;

    @BeforeEach
    void setUp() {
        properties = SplitterTestFixture.properties();
        TokenMeter tokenMeter = new TokenMeter();
        recursiveStrategy = new RecursiveBoundarySplitter(tokenMeter);
        markdownStrategy = new MarkdownStructuralSplitter(recursiveStrategy, tokenMeter, properties);
        tokenStrategy = new TokenFallbackSplitter();
        resolver = new TextChunkStrategyResolver(
                List.of(markdownStrategy, recursiveStrategy, tokenStrategy), properties);
    }

    // ==================== 路由矩阵 ====================

    @Test
    @DisplayName("按 docType 路由：MARKDOWN → markdown-structural，TXT/PDF/DOCX → recursive")
    void resolve_routeByDocType() {
        assertThat(resolver.resolve(context("MARKDOWN", null)).name())
                .isEqualTo(MarkdownStructuralSplitter.NAME);
        assertThat(resolver.resolve(context("TXT", null)).name())
                .isEqualTo(RecursiveBoundarySplitter.NAME);
        assertThat(resolver.resolve(context("PDF", null)).name())
                .isEqualTo(RecursiveBoundarySplitter.NAME);
        assertThat(resolver.resolve(context("DOCX", null)).name())
                .isEqualTo(RecursiveBoundarySplitter.NAME);
    }

    @Test
    @DisplayName("空上下文与 null 上下文走默认策略 recursive")
    void resolve_emptyContextUsesDefault() {
        assertThat(resolver.resolve(SplitContext.empty()).name())
                .isEqualTo(RecursiveBoundarySplitter.NAME);
        assertThat(resolver.resolve(null).name())
                .isEqualTo(RecursiveBoundarySplitter.NAME);
    }

    @Test
    @DisplayName("strategyOverride 显式指定优先于类型路由；未知策略名回退类型路由")
    void resolve_overrideWins() {
        assertThat(resolver.resolve(context("MARKDOWN", TokenFallbackSplitter.NAME)).name())
                .isEqualTo(TokenFallbackSplitter.NAME);
        // 未知策略名：回退 MARKDOWN 类型路由
        assertThat(resolver.resolve(context("MARKDOWN", "no-such-strategy")).name())
                .isEqualTo(MarkdownStructuralSplitter.NAME);
    }

    // ==================== 配置回退旧口径 ====================

    @Test
    @DisplayName("default-strategy: token 时默认路由回退旧 TokenTextSplitter 口径")
    void resolve_defaultStrategyRollback() {
        properties.setDefaultStrategy(TokenFallbackSplitter.NAME);

        assertThat(resolver.resolve(SplitContext.empty()).name())
                .isEqualTo(TokenFallbackSplitter.NAME);
    }

    @Test
    @DisplayName("markdown.strategy: token 时 MARKDOWN 回退旧口径（其余类型不受影响）")
    void resolve_markdownStrategyRollback() {
        properties.getMarkdown().setStrategy(TokenFallbackSplitter.NAME);

        assertThat(resolver.resolve(context("MARKDOWN", null)).name())
                .isEqualTo(TokenFallbackSplitter.NAME);
        assertThat(resolver.resolve(context("TXT", null)).name())
                .isEqualTo(RecursiveBoundarySplitter.NAME);
    }

    // ==================== 执行与打标 ====================

    @Test
    @DisplayName("split 为每个块打 splitStrategy 与 splitVersion 标记")
    void split_tagsStrategyMetadata() {
        List<Document> chunks = resolver.split(
                List.of(new Document(sentences(1, 10))), context("TXT", null));

        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            assertThat(chunk.getMetadata())
                    .containsEntry("splitStrategy", RecursiveBoundarySplitter.NAME)
                    .containsEntry("splitVersion", TextChunkStrategy.CURRENT_SPLIT_VERSION);
        }
    }

    @Test
    @DisplayName("MARKDOWN 上下文打 markdown-structural 标记")
    void split_tagsMarkdownStrategy() {
        List<Document> chunks = resolver.split(
                List.of(new Document("# 标题\n内容说明。\n")), context("MARKDOWN", null));

        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            assertThat(chunk.getMetadata())
                    .containsEntry("splitStrategy", MarkdownStructuralSplitter.NAME)
                    .containsEntry("splitVersion", TextChunkStrategy.CURRENT_SPLIT_VERSION);
        }
    }

    @Test
    @DisplayName("策略执行异常时降级 token 兜底，切分永不失败")
    void split_strategyFailureDegradesToToken() {
        TextChunkStrategy boom = new TextChunkStrategy() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public int order() {
                return 1;
            }

            @Override
            public boolean supports(SplitContext context) {
                return true;
            }

            @Override
            public List<Document> split(List<Document> docs, SplitOptions options) {
                throw new IllegalStateException("故意失败");
            }
        };
        TextChunkStrategyResolver boomResolver = new TextChunkStrategyResolver(
                List.of(boom, tokenStrategy), properties);

        List<Document> chunks = boomResolver.split(List.of(new Document(sentences(1, 10))), SplitContext.empty());

        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            assertThat(chunk.getMetadata()).containsEntry("splitStrategy", TokenFallbackSplitter.NAME);
        }
    }

    @Test
    @DisplayName("空文档列表原样返回空结果")
    void split_emptyDocs() {
        assertThat(resolver.split(List.of(), SplitContext.empty())).isEmpty();
    }

    // ==================== 测试助手 ====================

    private static SplitContext context(String docType, String strategyOverride) {
        return new SplitContext(docType, "file." + docType.toLowerCase(), null, null, null, strategyOverride);
    }

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
