package com.example.opsaiagent.retrieval.diversity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetrievalDiversifier 单元测试
 * 覆盖：同文档配额 / 邻近合并（含 String 与 Integer 两种 ordinal 口径）/ 禁用回退 / 元数据缺失兜底
 */
@DisplayName("检索同文档多样性治理")
class RetrievalDiversifierTest {

    private RetrievalDiversifier diversifier;

    @BeforeEach
    void setUp() {
        diversifier = new RetrievalDiversifier();
        ReflectionTestUtils.setField(diversifier, "enabled", true);
        ReflectionTestUtils.setField(diversifier, "maxPerDoc", 2);
        ReflectionTestUtils.setField(diversifier, "mergeAdjacent", true);
    }

    /** 生产口径：ordinal 为 String */
    private Document chunk(String docId, int ordinal, String text) {
        Map<String, Object> md = new HashMap<>();
        md.put("docId", docId);
        md.put("ordinal", String.valueOf(ordinal));
        return new Document(text, md);
    }

    /** 评测口径：ordinal 为 Integer */
    private Document chunkInt(String docId, int ordinal, String text) {
        Map<String, Object> md = new HashMap<>();
        md.put("docId", docId);
        md.put("ordinal", ordinal);
        return new Document(text, md);
    }

    @Test
    @DisplayName("同文档配额：超额块被让位，后排文档回填进 top-K")
    void maxPerDoc_capsAndBackfills() {
        List<Document> ranked = List.of(
                chunk("A", 1, "a1"), chunk("A", 5, "a5"), chunk("A", 9, "a9"),
                chunk("B", 2, "b2"));

        List<Document> result = diversifier.diversify(ranked, 3);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(d -> d.getMetadata().get("docId")))
                .containsExactly("A", "A", "B");
        assertThat(result.get(2).getText()).isEqualTo("b2");
    }

    @Test
    @DisplayName("邻近合并：同文档相邻 ordinal 并入已保留位，不占新名额")
    void mergeAdjacent_freesSlot() {
        List<Document> ranked = List.of(
                chunk("A", 3, "a3"), chunk("A", 4, "a4"), chunk("B", 1, "b1"));

        List<Document> result = diversifier.diversify(ranked, 3);

        // a4 并入 a3（占 1 个名额），b1 回填第 2 名额
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("a3\na4");
        assertThat(result.get(1).getText()).isEqualTo("b1");
        // 合并块元数据沿用 rank 更高的块
        assertThat(result.get(0).getMetadata().get("docId")).isEqualTo("A");
        assertThat(result.get(0).getMetadata().get("ordinal")).isEqualTo("3");
    }

    @Test
    @DisplayName("邻近合并：每个保留位最多吸收 1 块，防止链式合并")
    void mergeAdjacent_atMostOneAbsorption() {
        List<Document> ranked = List.of(
                chunk("A", 3, "a3"), chunk("A", 4, "a4"), chunk("A", 5, "a5"));

        List<Document> result = diversifier.diversify(ranked, 5);

        // a5 不再并入（吸收上限 1），作为第 2 个保留位（配额内）
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("a3\na4");
        assertThat(result.get(1).getText()).isEqualTo("a5");
    }

    @Test
    @DisplayName("评测口径 ordinal=Integer 同样参与合并判定")
    void mergeAdjacent_integerOrdinal() {
        List<Document> ranked = List.of(
                chunkInt("A", 7, "a7"), chunkInt("A", 8, "a8"));

        List<Document> result = diversifier.diversify(ranked, 2);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("a7\na8");
    }

    @Test
    @DisplayName("同文档非相邻块：正常占用配额名额")
    void nonAdjacent_occupiesSeparateSlots() {
        List<Document> ranked = List.of(
                chunk("A", 1, "a1"), chunk("A", 3, "a3"));

        List<Document> result = diversifier.diversify(ranked, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("a1");
        assertThat(result.get(1).getText()).isEqualTo("a3");
    }

    @Test
    @DisplayName("禁用时：按原序截断，不做任何治理")
    void disabled_passThrough() {
        ReflectionTestUtils.setField(diversifier, "enabled", false);
        List<Document> ranked = List.of(
                chunk("A", 1, "a1"), chunk("A", 2, "a2"), chunk("A", 3, "a3"));

        List<Document> result = diversifier.diversify(ranked, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("a1");
        assertThat(result.get(1).getText()).isEqualTo("a2");
    }

    @Test
    @DisplayName("元数据缺失（无 docId/source）：每块独立，不配额不合并")
    void missingMetadata_noGovernance() {
        List<Document> ranked = List.of(
                new Document("x1", Map.of()),
                new Document("x2", Map.of()),
                new Document("x3", Map.of()));

        List<Document> result = diversifier.diversify(ranked, 3);

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("无 docId 但有 source：按 source 分组配额")
    void sourceFallback_grouping() {
        Document s1 = new Document("s1", Map.of("source", "manual.md"));
        Document s2 = new Document("s2", Map.of("source", "manual.md"));
        Document s3 = new Document("s3", Map.of("source", "manual.md"));
        Document other = new Document("o1", Map.of("source", "other.md"));

        List<Document> result = diversifier.diversify(List.of(s1, s2, s3, other), 4);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(Document::getText))
                .containsExactly("s1", "s2", "o1");
    }

    @Test
    @DisplayName("top-1 永远保留（治理不改变首位）")
    void top1AlwaysKept() {
        List<Document> ranked = List.of(
                chunk("A", 1, "a1"), chunk("A", 2, "a2"), chunk("A", 3, "a3"),
                chunk("A", 4, "a4"), chunk("B", 1, "b1"));

        List<Document> result = diversifier.diversify(ranked, 5);

        assertThat(result.get(0).getText()).isEqualTo("a1\na2");
    }
}
