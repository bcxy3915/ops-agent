package com.example.opsaiagent.knowledge.service;

import org.mockito.ArgumentMatchers;
import org.assertj.core.data.Offset;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalTestRequest;
import com.example.opsaiagent.knowledge.dto.RetrievalTestResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.retrieval.HybridRetriever;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.opsaiagent.knowledge.model.Document;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 检索测试服务单元测试（T18）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("检索测试服务")
class KnowledgeRetrievalServiceTest {

    @Mock
    private HybridRetriever hybridRetriever;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private EmbeddingModel embeddingModel;

    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeRetrievalService(hybridRetriever, documentMapper, embeddingModel);
        lenient().when(hybridRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        // 默认夹具模拟相关正文，具体误召回用例单独覆盖向量响应。
        lenient().when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            return Response.from(IntStream.range(0, segments.size())
                    .mapToObj(index -> Embedding.from(index == 2
                            ? new float[]{0.8f, 0.6f} : new float[]{1, 0})).toList());
        });
    }

    private Document doc(String id, String text, Map<String, Object> metadata) {
        return new Document(id, text, metadata);
    }

    private KnowledgeDocumentEntity dbDoc(Long id, String title, String layer, String status, Integer version, String source) {
        KnowledgeDocumentEntity e = new KnowledgeDocumentEntity();
        e.setId(id);
        e.setTitle(title);
        e.setLayer(layer);
        e.setTrustStatus(status);
        e.setVersion(version);
        e.setSource(source);
        return e;
    }

    // ==================== test ====================

    @Test
    @DisplayName("test：查询文本为空抛参数异常")
    void test_blankQuery() {
        RetrievalTestRequest req = new RetrievalTestRequest();
        req.setQuery("  ");
        assertThatThrownBy(() -> service.test(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("test：正常返回命中并补齐 DB 元数据")
    void test_hitsWithDbMeta() {
        when(hybridRetriever.retrieve("故障", 5)).thenReturn(List.of(
                doc("vec-1", "服务 A 故障排查步骤说明",
                        Map.of("source", "upload:10", "docId", "10", "chunkId", "101",
                                "title", "旧标题", "layer", "PRIVATE", "trustStatus", "PENDING")),
                doc("vec-2", "数据库连接失败处理",
                        Map.of("source", "01-服务清单.md", "title", "内置手册"))
        ));
        when(documentMapper.selectById(10L)).thenReturn(dbDoc(10L, "上传手册", "PRIVATE", "VERIFIED", 2, "upload:manual.md"));

        RetrievalTestResponse r = service.test(req("故障", 5, null));

        assertThat(r.getCount()).isEqualTo(2);
        RetrievalHitResponse hit0 = r.getHits().get(0);
        assertThat(hit0.getDocId()).isEqualTo(10L);
        assertThat(hit0.getTitle()).isEqualTo("上传手册");
        assertThat(hit0.getTrustStatus()).isEqualTo("VERIFIED");
        assertThat(hit0.getVersion()).isEqualTo(2);
        assertThat(hit0.getChunkId()).isEqualTo(101L);
        assertThat(hit0.getSnippet()).contains("<mark>");
        assertThat(hit0.getScore()).isEqualTo(1.0);

        RetrievalHitResponse hit1 = r.getHits().get(1);
        assertThat(hit1.getSource()).isEqualTo("01-服务清单.md");
        assertThat(hit1.getTitle()).isEqualTo("内置手册");
        assertThat(hit1.getScore()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("test：metadata 有真实值且反查不到时回退 metadata")
    void test_fallbackToMetadata() {
        when(hybridRetriever.retrieve("查询", 3)).thenReturn(List.of(
                doc("vec-1", "知识片段内容",
                        Map.of("source", "upload:99", "docId", "99", "title", "M标题",
                                "layer", "PRIVATE", "trustStatus", "VERIFIED", "version", "5"))
        ));
        when(documentMapper.selectById(99L)).thenReturn(null);

        RetrievalTestResponse r = service.test(req("查询", 3, null));

        RetrievalHitResponse hit = r.getHits().get(0);
        assertThat(hit.getTitle()).isEqualTo("M标题");
        assertThat(hit.getTrustStatus()).isEqualTo("VERIFIED");
        assertThat(hit.getVersion()).isEqualTo(5);
        assertThat(hit.getDocId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("test：内置文档按 source 反查")
    void test_resolveBySource() {
        when(hybridRetriever.retrieve("部署", 3)).thenReturn(List.of(
                doc("vec-1", "部署手册内容",
                        Map.of("source", "04-部署手册.md"))
        ));
        when(documentMapper.selectOne(ArgumentMatchers.any()))
                .thenReturn(dbDoc(4L, "部署手册", "BUILTIN", "VERIFIED", 1, "04-部署手册.md"));

        RetrievalTestResponse r = service.test(req("部署", 3, null));

        RetrievalHitResponse hit = r.getHits().get(0);
        assertThat(hit.getDocId()).isEqualTo(4L);
        assertThat(hit.getLayer()).isEqualTo("BUILTIN");
    }

    @Test
    @DisplayName("test：按层级过滤命中")
    void test_filterByLayer() {
        when(hybridRetriever.retrieve("故障", 10)).thenReturn(List.of(
                doc("vec-1", "内置故障内容", Map.of("source", "a.md", "layer", "BUILTIN")),
                doc("vec-2", "私有上传内容", Map.of("source", "upload:1", "docId", "1", "layer", "PRIVATE"))
        ));

        RetrievalTestResponse r = service.test(req("故障", 10, "BUILTIN"));

        assertThat(r.getCount()).isEqualTo(1);
        assertThat(r.getHits().get(0).getLayer()).isEqualTo("BUILTIN");
    }

    @Test
    @DisplayName("test：topK 越界收敛到 1~20")
    void test_topKBounds() {
        when(hybridRetriever.retrieve("故障", 1)).thenReturn(List.of(
                doc("v1", "内容1", Map.of("source", "a.md"))));
        when(hybridRetriever.retrieve("故障", 20)).thenReturn(List.of(
                doc("v1", "内容1", Map.of("source", "a.md")),
                doc("v2", "内容2", Map.of("source", "b.md"))));

        RetrievalTestResponse r1 = service.test(req("故障", 0, null));
        assertThat(r1.getTopK()).isEqualTo(1);

        RetrievalTestResponse r2 = service.test(req("故障", 99, null));
        assertThat(r2.getTopK()).isEqualTo(20);
    }

    @Test
    @DisplayName("test：检索为空返回空命中")
    void test_emptyHits() {
        RetrievalTestResponse r = service.test(req("不存在的东西", 5, null));
        assertThat(r.getCount()).isZero();
        assertThat(r.getHits()).isEmpty();
    }

    @Test
    @DisplayName("test：null topK 使用默认 5")
    void test_defaultTopK() {
        when(hybridRetriever.retrieve("故障", 5)).thenReturn(List.of(
                doc("v1", "内容", Map.of("source", "a.md"))));
        RetrievalTestRequest req = new RetrievalTestRequest();
        req.setQuery("故障");
        RetrievalTestResponse r = service.test(req);
        assertThat(r.getTopK()).isEqualTo(5);
    }

    // ==================== highlight ====================

    @Test
    @DisplayName("highlight：整体命中高亮")
    void highlight_fullMatch() {
        String s = service.highlight("本手册介绍故障排查流程", "故障排查");
        assertThat(s).contains("<mark>故障排查</mark>");
        assertThat(s).contains("手册介绍");
    }

    @Test
    @DisplayName("highlight：大小写不敏感命中")
    void highlight_caseInsensitive() {
        String s = service.highlight("Check the CPU Usage value", "cpu");
        assertThat(s.toLowerCase()).contains("<mark>cpu</mark>");
    }

    @Test
    @DisplayName("highlight：2-gram 片段命中")
    void highlight_biGram() {
        String s = service.highlight("服务重启后仍无法连接数据库", "数据库故障");
        assertThat(s).contains("<mark>数据库</mark>");
    }

    @Test
    @DisplayName("highlight：无命中取前缀")
    void highlight_noMatch() {
        String s = service.highlight("完全无关的文档内容", "星空");
        assertThat(s).doesNotContain("<mark>");
        assertThat(s).isEqualTo("完全无关的文档内容");
    }

    @Test
    @DisplayName("highlight：空文本返回空串")
    void highlight_blankText() {
        assertThat(service.highlight("", "q")).isEmpty();
        assertThat(service.highlight("  ", "q")).isEmpty();
        assertThat(service.highlight(null, "q")).isEmpty();
    }

    @Test
    @DisplayName("highlight：空查询取前缀")
    void highlight_blankQuery() {
        String s = service.highlight("很长的一段文本".repeat(50), "  ");
        assertThat(s).doesNotContain("<mark>");
        assertThat(s.length()).isLessThanOrEqualTo(200 + 2);
    }

    @Test
    @DisplayName("highlight：长文本截断带省略号")
    void highlight_truncate() {
        String longText = "x".repeat(100) + "故障" + "y".repeat(100);
        String s = service.highlight(longText, "故障");
        assertThat(s).contains("<mark>故障</mark>");
        // 内容不超过 200 字符 + mark 标签 13 字符 + 两端省略号 2 字符
        assertThat(s.length()).isLessThanOrEqualTo(215);
        assertThat(s).startsWith("…");
    }

    // ==================== extractTerms ====================

    @Test
    @DisplayName("extractTerms：英文整词与中文 2-gram 去重")
    void extractTerms_ok() {
        List<String> terms = service.extractTerms("cpu 故障排查");
        assertThat(terms).contains("cpu", "故障", "排查", "障排");
        assertThat(terms.stream().filter(t -> t.equals("故障")).count()).isEqualTo(1);
    }

    // ==================== toHits ====================

    @Test
    @DisplayName("toHits：正常返回结构化引用并补齐 DB 元数据")
    void toHits_ok() {
        List<Document> docs = List.of(
                doc("vec-1", "数据库连接数过高，需要检查连接池配置",
                        Map.of("source", "upload:10", "docId", "10", "chunkId", "101",
                                "title", "旧标题", "layer", "PRIVATE", "trustStatus", "PENDING")),
                doc("vec-2", "服务重启排查步骤",
                        Map.of("source", "01-服务清单.md", "title", "内置手册"))
        );
        when(documentMapper.selectById(10L)).thenReturn(dbDoc(10L, "上传手册", "PRIVATE", "VERIFIED", 2, "upload:manual.md"));
        when(documentMapper.selectOne(ArgumentMatchers.any()))
                .thenReturn(dbDoc(5L, "服务清单", "BUILTIN", "VERIFIED", 1, "01-服务清单.md"));

        List<RetrievalHitResponse> hits = service.toHits(docs, "数据库故障", 5);

        assertThat(hits).hasSize(2);
        RetrievalHitResponse hit0 = hits.get(0);
        assertThat(hit0.getDocId()).isEqualTo(10L);
        assertThat(hit0.getChunkId()).isEqualTo(101L);
        assertThat(hit0.getTitle()).isEqualTo("上传手册");
        assertThat(hit0.getLayer()).isEqualTo("PRIVATE");
        assertThat(hit0.getTrustStatus()).isEqualTo("VERIFIED");
        assertThat(hit0.getVersion()).isEqualTo(2);
        assertThat(hit0.getSource()).isEqualTo("upload:manual.md");
        assertThat(hit0.getSnippet()).contains("<mark>");
        assertThat(hit0.getScore()).isEqualTo(1.0);

        RetrievalHitResponse hit1 = hits.get(1);
        assertThat(hit1.getDocId()).isEqualTo(5L);
        assertThat(hit1.getTitle()).isEqualTo("服务清单");
        assertThat(hit1.getLayer()).isEqualTo("BUILTIN");
        assertThat(hit1.getTrustStatus()).isEqualTo("VERIFIED");
        assertThat(hit1.getVersion()).isEqualTo(1);
        assertThat(hit1.getScore()).isCloseTo(0.8, Offset.offset(0.000001));
    }

    @Test
    @DisplayName("toHits：null 或空列表返回空列表")
    void toHits_emptyOrNull() {
        assertThat(service.toHits(null, "q", 5)).isEmpty();
        assertThat(service.toHits(List.of(), "q", 5)).isEmpty();
    }

    @Test
    @DisplayName("toHits：反查不到 DB 时回退 metadata 并保留真实值")
    void toHits_fallbackMetadata() {
        List<Document> docs = List.of(
                doc("vec-1", "知识片段内容",
                        Map.of("source", "upload:99", "docId", "99", "title", "M标题",
                                "layer", "PRIVATE", "trustStatus", "VERIFIED", "version", "5"))
        );
        when(documentMapper.selectById(99L)).thenReturn(null);

        List<RetrievalHitResponse> hits = service.toHits(docs, "查询", 3);

        assertThat(hits).hasSize(1);
        RetrievalHitResponse hit = hits.get(0);
        assertThat(hit.getDocId()).isEqualTo(99L);
        assertThat(hit.getTitle()).isEqualTo("M标题");
        assertThat(hit.getTrustStatus()).isEqualTo("VERIFIED");
        assertThat(hit.getVersion()).isEqualTo(5);
        assertThat(hit.getSnippet()).contains("知识片段");
    }

    @Test
    @DisplayName("toHits：空文本 Document 被过滤")
    void toHits_skipBlankDoc() {
        List<Document> docs = List.of(
                doc("vec-1", "", Map.of("source", "a.md")),
                doc("vec-2", "有效内容", Map.of("source", "b.md"))
        );

        List<RetrievalHitResponse> hits = service.toHits(docs, "内容", 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getSnippet()).contains("有效<mark>内容</mark>");
    }

    /**
     * 验证跨领域问题即使被 BM25 排到首位，也不生成文档来源。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void toHits_unrelatedQuestionHasNoSources() {
        Document candidate = new Document("weak", "MySQL 连接池排查", Map.of(), 15.0);
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                Embedding.from(new float[]{1, 0}), Embedding.from(new float[]{0.3f, 0.95f}))));

        assertThat(service.toHits(List.of(candidate), "如何制作红烧肉", 5)).isEmpty();
        assertThat(candidate.getScore()).isEqualTo(15.0);
        verifyNoInteractions(documentMapper, hybridRetriever);
    }

    /**
     * 验证混合候选仅展示相关来源，且分数不因排名靠前而变成满分。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void toHits_filtersMixedCandidatesAndUsesSemanticScore() {
        List<Document> docs = List.of(doc("unrelated", "天气预报", Map.of()),
                doc("relevant", "数据库连接池耗尽排查", Map.of()));
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                Embedding.from(new float[]{1, 0}), Embedding.from(new float[]{0, 1}),
                Embedding.from(new float[]{0.8f, 0.6f}))));

        List<RetrievalHitResponse> hits = service.toHits(docs, "连接池满了怎么办", 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().getSnippet()).contains("连接池");
        assertThat(hits.getFirst().getScore()).isCloseTo(0.8, Offset.offset(0.000001));
        assertThat(docs.get(1).getScore()).isNull();
        verifyNoInteractions(hybridRetriever);
    }

    /**
     * 验证外部校验失败时隐藏来源，不抛异常阻断问答。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void toHits_embeddingFailureHasNoSources() {
        when(embeddingModel.embedAll(anyList())).thenThrow(new IllegalStateException("模拟模型不可用"));
        assertThat(service.toHits(List.of(doc("a", "数据库排查", Map.of())), "排查数据库", 5)).isEmpty();
        verifyNoInteractions(documentMapper);
    }

    /**
     * 验证缺失、零向量或维度不一致的评分不会产生来源。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void toHits_invalidEmbeddingsHaveNoSources() {
        List<Document> docs = List.of(doc("a", "正文", Map.of()));
        when(embeddingModel.embedAll(anyList())).thenReturn(
                Response.from(List.of(Embedding.from(new float[]{1, 0}))),
                Response.from(List.of(Embedding.from(new float[]{1, 0}), Embedding.from(new float[]{0, 0}))),
                Response.from(List.of(Embedding.from(new float[]{1, 0}), Embedding.from(new float[]{1}))));
        for (int i = 0; i < 3; i++) {
            assertThat(service.toHits(docs, "查询", 5)).isEmpty();
        }
        verifyNoInteractions(documentMapper);
    }

    /**
     * 验证空问题或无效数量不调用模型，且有效引用不超过数量上限。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void toHits_checksInputAndLimitsSources() {
        List<Document> docs = List.of(doc("a", "正文一", Map.of()), doc("b", "正文二", Map.of()));
        assertThat(service.toHits(docs, " ", 5)).isEmpty();
        assertThat(service.toHits(docs, "查询", 0)).isEmpty();
        verifyNoInteractions(embeddingModel);
        assertThat(service.toHits(docs, "查询", 1)).hasSize(1);
    }

    private RetrievalTestRequest req(String query, int topK, String layer) {
        RetrievalTestRequest r = new RetrievalTestRequest();
        r.setQuery(query);
        r.setTopK(topK);
        r.setLayer(layer);
        return r;
    }
}
