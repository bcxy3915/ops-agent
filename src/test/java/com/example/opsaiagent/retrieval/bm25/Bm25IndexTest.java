package com.example.opsaiagent.retrieval.bm25;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bm25Index 单元测试
 * 覆盖：构建索引、基础检索、topK、IDF 加权、重建、边界值
 */
@DisplayName("BM25 内存索引")
class Bm25IndexTest {

    private Bm25Index index;

    @BeforeEach
    void setUp() {
        index = new Bm25Index();
    }

    /** 构造带 id 的 Document 辅助方法 */
    private static Document doc(String id, String text) {
        return new Document(id, text, java.util.Map.of());
    }

    // ==================== 构建索引 ====================

    @Test
    @DisplayName("build：size() 返回文档数")
    void build_size() {
        index.build(List.of(
                doc("1", "内存泄漏排查"),
                doc("2", "CPU 使用率过高"),
                doc("3", "数据库连接池耗尽")
        ));
        assertThat(index.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("build：空列表不抛异常")
    void build_empty() {
        index.build(List.of());
        assertThat(index.size()).isZero();
    }

    @Test
    @DisplayName("build：重复调用会清空旧索引后重建")
    void build_clearsOldIndex() {
        index.build(List.of(doc("1", "内存泄漏"), doc("2", "CPU")));
        assertThat(index.size()).isEqualTo(2);

        index.build(List.of(doc("3", "数据库")));
        assertThat(index.size()).isEqualTo(1);

        // 旧文档已不可检索
        assertThat(index.search("内存", 5)).isEmpty();
    }

    // ==================== 基础检索 ====================

    @Test
    @DisplayName("search：命中关键词的文档优先返回")
    void search_basic() {
        index.build(List.of(
                doc("1", "内存泄漏排查思路"),
                doc("2", "CPU 使用率过高排查"),
                doc("3", "数据库连接池耗尽")
        ));

        List<Bm25Index.ScoredDocument> results = index.search("内存泄漏", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getDocument().getId()).isEqualTo("1");
    }

    @Test
    @DisplayName("search：topK 生效，返回条数不超过 topK")
    void search_topK() {
        index.build(List.of(
                doc("1", "Java 内存模型"),
                doc("2", "Java 线程池"),
                doc("3", "Java GC 调优"),
                doc("4", "Java 类加载")
        ));

        assertThat(index.search("Java", 2)).hasSize(2);
        assertThat(index.search("Java", 10)).hasSize(4);
    }

    @Test
    @DisplayName("search：分数降序排列")
    void search_scoreDescending() {
        index.build(List.of(
                doc("1", "内存内存内存"),
                doc("2", "内存"),
                doc("3", "CPU")
        ));

        List<Bm25Index.ScoredDocument> results = index.search("内存", 3);
        assertThat(results).hasSize(2);
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).getScore())
                    .isGreaterThanOrEqualTo(results.get(i).getScore());
        }
    }

    // ==================== 边界值 ====================

    @Test
    @DisplayName("search：无匹配返回空列表")
    void search_noMatch() {
        index.build(List.of(
                doc("1", "内存泄漏排查"),
                doc("2", "CPU 使用率过高")
        ));

        assertThat(index.search("完全不相干的词汇xyz", 5)).isEmpty();
    }

    @Test
    @DisplayName("search：空索引返回空列表")
    void search_emptyIndex() {
        assertThat(index.search("anything", 5)).isEmpty();
    }

    @Test
    @DisplayName("search：null / 空 / 空白查询返回空列表")
    void search_invalidQuery() {
        index.build(List.of(doc("1", "内存泄漏排查")));

        assertThat(index.search(null, 5)).isEmpty();
        assertThat(index.search("", 5)).isEmpty();
        assertThat(index.search("   ", 5)).isEmpty();
    }

    // ==================== 算法特性 ====================

    @Test
    @DisplayName("IDF 加权：稀有词比常见词分数更高")
    void search_idfWeight() {
        // 5 个文档中：
        // - "rare" 只出现 1 次 → df=1, IDF 高
        // - "common" 出现 3 次 → df=3, IDF 低
        index.build(List.of(
                doc("1", "rare"),
                doc("2", "common"),
                doc("3", "common"),
                doc("4", "common"),
                doc("5", "other")
        ));

        double rareScore = index.search("rare", 5).get(0).getScore();
        double commonScore = index.search("common", 5).get(0).getScore();

        assertThat(rareScore).isGreaterThan(commonScore);
    }

    @Test
    @DisplayName("词频加权：TF 越高分数越高")
    void search_tfBoost() {
        index.build(List.of(
                doc("1", "内存内存内存"),
                doc("2", "内存")
        ));

        List<Bm25Index.ScoredDocument> results = index.search("内存", 2);
        // doc 1 出现 3 次，doc 2 出现 1 次，doc 1 应排前面
        assertThat(results.get(0).getDocument().getId()).isEqualTo("1");
        assertThat(results.get(0).getScore())
                .isGreaterThan(results.get(1).getScore());
    }
}