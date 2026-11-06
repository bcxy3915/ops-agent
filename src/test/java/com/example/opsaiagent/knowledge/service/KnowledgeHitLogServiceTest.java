package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.knowledge.dto.KnowledgeHitLogResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeHitLogEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeHitLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识命中流水服务测试（P1）
 * 覆盖：空列表跳过、无 docId 跳过、正常写入、CASE 层案例引用累加、单条失败容错。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识命中流水服务")
class KnowledgeHitLogServiceTest {

    @Mock
    private KnowledgeHitLogMapper hitLogMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeCaseMapper caseMapper;

    @Spy
    private KnowledgeQueryScope queryScope = new KnowledgeQueryScope(null);

    @InjectMocks
    private KnowledgeHitLogService service;

    /** 构造命中项 */
    private RetrievalHitResponse hit(Long docId) {
        return RetrievalHitResponse.builder()
                .docId(docId)
                .title("文档" + docId)
                .source(docId != null ? "upload:" + docId : null)
                .build();
    }

    @Test
    @DisplayName("空列表/ null 列表：返回 0 且不落库")
    void recordHits_emptyOrNull() {
        assertThat(service.recordHits(null, "q", KnowledgeHitLogService.SOURCE_ASK, "u")).isZero();
        assertThat(service.recordHits(List.of(), "q", KnowledgeHitLogService.SOURCE_ASK, "u")).isZero();
        verify(hitLogMapper, never()).insert(any(KnowledgeHitLogEntity.class));
    }

    @Test
    @DisplayName("docId 为 null 的命中跳过，不计入返回")
    void recordHits_skipNullDocId() {
        int n = service.recordHits(List.of(hit(null), hit(null)), "q", KnowledgeHitLogService.SOURCE_ASK, "u");
        assertThat(n).isZero();
        verify(hitLogMapper, never()).insert(any(KnowledgeHitLogEntity.class));
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("正常写入：写流水 + 文档 hit_count +1，返回条数")
    void recordHits_ok() {
        int n = service.recordHits(List.of(hit(1L), hit(2L)), "数据库连接失败", KnowledgeHitLogService.SOURCE_ASK, "admin");
        assertThat(n).isEqualTo(2);
        verify(hitLogMapper, times(2)).insert(any(KnowledgeHitLogEntity.class));
        verify(documentMapper, times(2)).update(eq(null), any());
        verify(documentMapper, times(2)).selectById(any());
    }

    @Test
    @DisplayName("CASE 层文档命中：同步累加 knowledge_case.cited_count")
    void recordHits_caseLayerUpdateCitedCount() {
        KnowledgeDocumentEntity caseDoc = new KnowledgeDocumentEntity();
        caseDoc.setId(9L);
        caseDoc.setLayer("CASE");
        caseDoc.setSource("case:100");
        lenient().when(documentMapper.selectById(9L)).thenReturn(caseDoc);

        int n = service.recordHits(List.of(hit(9L)), "数据库连接失败", KnowledgeHitLogService.SOURCE_ASK, "admin");
        assertThat(n).isEqualTo(1);
        verify(caseMapper).update(eq(null), any());
        // caseMapper 更新参数里的 wrapper 必须 eq id=100
        verify(caseMapper).update(eq(null), argThat(w -> {
            String sql = String.valueOf(w.getSqlSet());
            return sql != null && sql.contains("cited_count = COALESCE(cited_count, 0) + 1");
        }));
    }

    @Test
    @DisplayName("非 CASE 层文档：不更新案例引用数")
    void recordHits_privateLayerNoCaseUpdate() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(5L);
        doc.setLayer("PRIVATE");
        doc.setSource("upload:5");
        lenient().when(documentMapper.selectById(5L)).thenReturn(doc);

        service.recordHits(List.of(hit(5L)), "q", KnowledgeHitLogService.SOURCE_ASK, "admin");
        verify(caseMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("case: 前缀但非数字 source：跳过案例更新，流水仍写入")
    void recordHits_caseSourceNotNumeric() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(7L);
        doc.setLayer("CASE");
        doc.setSource("case:abc");
        lenient().when(documentMapper.selectById(7L)).thenReturn(doc);

        int n = service.recordHits(List.of(hit(7L)), "q", KnowledgeHitLogService.SOURCE_ASK, "admin");
        assertThat(n).isEqualTo(1);
        verify(caseMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("单条写入失败：跳过该条，其余命中仍记录")
    void recordHits_partialFailureTolerant() {
        doThrow(new RuntimeException("db down")).when(hitLogMapper).insert(any(KnowledgeHitLogEntity.class));

        int n = service.recordHits(List.of(hit(1L), hit(2L)), "q", KnowledgeHitLogService.SOURCE_ASK, "admin");
        assertThat(n).isZero();
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("query 超长：截断到 500 再入库")
    void recordHits_truncateLongQuery() {
        String longQuery = "长".repeat(800);
        int n = service.recordHits(List.of(hit(1L)), longQuery, KnowledgeHitLogService.SOURCE_ASK, "admin");
        assertThat(n).isEqualTo(1);
        verify(hitLogMapper).insert(argThat((KnowledgeHitLogEntity e) -> e.getQuery() != null
                && e.getQuery().length() <= 501
                && e.getQuery().endsWith("…")));
    }

    // ==================== P2-② 单篇命中反查 ====================

    @Test
    @DisplayName("listByDocument：分页返回该文档命中流水")
    void listByDocument_paged() {
        KnowledgeHitLogEntity h1 = new KnowledgeHitLogEntity();
        h1.setId(1L);
        h1.setDocId(9L);
        h1.setQuery("q1");
        h1.setHitSource("ASK");
        h1.setChannel("admin");
        KnowledgeHitLogEntity h2 = new KnowledgeHitLogEntity();
        h2.setId(2L);
        h2.setDocId(9L);
        h2.setQuery("q2");
        h2.setHitSource("TEST");
        h2.setChannel("tester");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<KnowledgeHitLogEntity> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 2);
        page.setRecords(List.of(h1, h2));
        when(hitLogMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<KnowledgeHitLogResponse> r =
                service.listByDocument(9L, 1, 10);

        assertThat(r.getTotal()).isEqualTo(2);
        assertThat(r.getRecords()).hasSize(2);
        assertThat(r.getRecords().get(0).getHitSource()).isEqualTo("ASK");
    }

    // ==================== P2-④ 案例引用统计 ====================

    @Test
    @DisplayName("listCaseReferences：案例文档存在时返回命中总数与最近流水")
    void listCaseReferences_found() {
        KnowledgeDocumentEntity caseDoc = new KnowledgeDocumentEntity();
        caseDoc.setId(20L);
        caseDoc.setLayer("CASE");
        caseDoc.setSource("case:5");
        when(documentMapper.selectOne(any())).thenReturn(caseDoc);
        when(hitLogMapper.selectCount(any())).thenReturn(3L);
        KnowledgeHitLogEntity h = new KnowledgeHitLogEntity();
        h.setId(1L);
        h.setDocId(20L);
        h.setQuery("q");
        h.setHitSource("ASK");
        when(hitLogMapper.selectList(any())).thenReturn(List.of(h));

        com.example.opsaiagent.knowledge.dto.KnowledgeCaseReferenceResponse r =
                service.listCaseReferences(5L);

        assertThat(r.getCaseId()).isEqualTo(5L);
        assertThat(r.getDocId()).isEqualTo(20L);
        assertThat(r.getTotalHits()).isEqualTo(3L);
        assertThat(r.getRecentHits()).hasSize(1);
        assertThat(r.getRecentHits().get(0).getQuery()).isEqualTo("q");
    }

    @Test
    @DisplayName("listCaseReferences：案例文档不存在返回 totalHits=0")
    void listCaseReferences_notFound() {
        when(documentMapper.selectOne(any())).thenReturn(null);

        com.example.opsaiagent.knowledge.dto.KnowledgeCaseReferenceResponse r =
                service.listCaseReferences(99L);

        assertThat(r.getDocId()).isNull();
        assertThat(r.getTotalHits()).isZero();
        assertThat(r.getRecentHits()).isEmpty();
        verify(hitLogMapper, never()).selectCount(any());
    }
}
