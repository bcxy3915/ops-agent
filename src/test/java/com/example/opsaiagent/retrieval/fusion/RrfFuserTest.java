package com.example.opsaiagent.retrieval.fusion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RrfFuser 单元测试
 * 覆盖：双路融合、单路退化、去重、topK 限制、空/null 边界
 * 公式：score(d) = Σ 1 / (K + rank + 1)，K = 60
 */
@SuppressWarnings("unchecked")
@DisplayName("RRF 融合器")
class RrfFuserTest {

    private RrfFuser fuser;

    @BeforeEach
    void setUp() {
        fuser = new RrfFuser();
    }

    private static Document doc(String id, String text) {
        return new Document(id, text, java.util.Map.of());
    }

    // ==================== 双路融合 ====================

    @Test
    @DisplayName("两路都命中：分数最高，排最前")
    void fuse_bothHit_ranksFirst() {
        // ListA: A(rank 0), B(rank 1), C(rank 2)
        // ListB: C(rank 0), A(rank 1), D(rank 2)
        //
        // A: 1/61 + 1/62  ≈ 0.03252
        // C: 1/63 + 1/61  ≈ 0.03226
        // B: 1/62         ≈ 0.01613
        // D: 1/63         ≈ 0.01587
        //
        // 预期顺序：A, C, B, D
        List<Document> listA = List.of(
                doc("A", "文档A"),
                doc("B", "文档B"),
                doc("C", "文档C")
        );
        List<Document> listB = List.of(
                doc("C", "文档C"),
                doc("A", "文档A"),
                doc("D", "文档D")
        );

        List<Document> fused = fuser.fuse(listA, listB);

        assertThat(fused).extracting(Document::getId)
                .containsExactly("A", "C", "B", "D");
    }

    @Test
    @DisplayName("三路融合：所有列表都命中的排最前")
    void fuse_threeLists() {
        Document x = doc("X", "X");
        Document y = doc("Y", "Y");

        List<Document> l1 = List.of(x, y);
        List<Document> l2 = List.of(x);
        List<Document> l3 = List.of(x);

        List<Document> fused = fuser.fuse(l1, l2, l3);

        // X 在三路出现，分数最高
        assertThat(fused.get(0).getId()).isEqualTo("X");
    }

    // ==================== 单路 / 空 / null ====================

    @Test
    @DisplayName("单路输入：退化为原顺序")
    void fuse_singleList() {
        List<Document> list = List.of(
                doc("1", "一"),
                doc("2", "二"),
                doc("3", "三")
        );

        List<Document> fused = fuser.fuse(list);

        assertThat(fused).extracting(Document::getId)
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("空输入：返回空列表")
    void fuse_emptyLists() {
        assertThat(fuser.fuse()).isEmpty();
        assertThat(fuser.fuse(List.of())).isEmpty();
        assertThat(fuser.fuse(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("null 列表：跳过，不抛异常")
    void fuse_nullListSkipped() {
        List<Document> listA = List.of(doc("A", "A"));
        List<Document> fused = fuser.fuse(listA, null);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getId()).isEqualTo("A");
    }

    // ==================== 去重 ====================

    @Test
    @DisplayName("去重：同一文档在多列表出现只保留一份")
    void fuse_dedup() {
        List<Document> listA = List.of(
                doc("X", "同一文档"),
                doc("Y", "另一个")
        );
        List<Document> listB = List.of(
                doc("X", "同一文档")
        );

        List<Document> fused = fuser.fuse(listA, listB);

        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(Document::getId)
                .containsExactlyInAnyOrder("X", "Y");
    }

    // ==================== fuseAndLimit ====================

    @Test
    @DisplayName("fuseAndLimit：限制返回条数")
    void fuseAndLimit_basic() {
        List<Document> listA = List.of(
                doc("1", "一"),
                doc("2", "二"),
                doc("3", "三")
        );

        List<Document> fused = fuser.fuseAndLimit(2, listA);
        assertThat(fused).hasSize(2);
    }

    @Test
    @DisplayName("fuseAndLimit：topK 大于结果数时返回全部")
    void fuseAndLimit_topKGreater() {
        List<Document> listA = List.of(doc("1", "一"));
        List<Document> fused = fuser.fuseAndLimit(10, listA);
        assertThat(fused).hasSize(1);
    }

    @Test
    @DisplayName("fuseAndLimit：topK = 0 返回空列表")
    void fuseAndLimit_zero() {
        List<Document> listA = List.of(doc("1", "一"), doc("2", "二"));
        assertThat(fuser.fuseAndLimit(0, listA)).isEmpty();
    }

    // ==================== 排序验证 ====================

    @Test
    @DisplayName("分数降序：两路都命中的文档排最前")
    void fuse_scoreOrder() {
        List<Document> listA = List.of(
                doc("A", "A"),
                doc("B", "B")
        );
        List<Document> listB = List.of(
                doc("A", "A"),
                doc("C", "C")
        );

        List<Document> fused = fuser.fuse(listA, listB);

        // A 在两路都出现，分数应最高
        assertThat(fused.get(0).getId()).isEqualTo("A");
    }
}