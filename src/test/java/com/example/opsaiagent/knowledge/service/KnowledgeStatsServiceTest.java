package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.knowledge.dto.KnowledgeCoverageResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHealthResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeStatsResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeTodoResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeConflictEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeHitLogEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeConflictMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeHitLogMapper;
import com.example.opsaiagent.mapper.OpsServiceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 知识库统计聚合服务测试（P1）
 * 覆盖：总览统计各维度、覆盖度（含空服务/空名跳过）、待办聚合四类目、serviceIds 解析。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识库统计聚合服务")
class KnowledgeStatsServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeCaseMapper caseMapper;
    @Mock
    private KnowledgeHitLogMapper hitLogMapper;
    @Mock
    private KnowledgeConflictMapper conflictMapper;
    @Mock
    private OpsServiceMapper serviceMapper;

    private KnowledgeStatsService service;

    /** 初始化 MyBatis-Plus 实体缓存，否则 Lambda wrapper 在单测环境无法解析 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeCaseEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeConflictEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeHitLogEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 手动构造：ObjectMapper 无需 mock，用真实实例
        service = new KnowledgeStatsService(
                documentMapper, caseMapper, hitLogMapper, conflictMapper, serviceMapper, new ObjectMapper());
    }

    private OpsServiceEntity service(String name) {
        OpsServiceEntity e = new OpsServiceEntity();
        e.setName(name);
        return e;
    }

    @Test
    @DisplayName("总览统计：各维度计数正确映射")
    void stats_allCounts() {
        when(documentMapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            if (w == null) {
                return 10L; // totalDocs
            }
            // 列名在 getTargetSql 中，参数值在 getParamNameValuePairs 中，分别判断
            String sql = w.getTargetSql();
            if (sql != null && sql.contains("review_at")) {
                return 2L; // expiredDocs
            }
            if (sql != null && sql.contains("hit_count")) {
                return 4L; // zeroHitDocs
            }
            if (sql != null && sql.contains("chunk_count")) {
                return 1L; // orphanDocs
            }
            if (sql != null && sql.contains("trust_status")) {
                return 3L; // pendingDocs
            }
            return 0L;
        });
        // caseMapper.selectCount 固定调用顺序：null(总数) → PENDING → CONFIRMED，按顺序计数返回
        AtomicInteger caseCalls = new AtomicInteger();
        when(caseMapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            if (w == null) {
                return 5L; // totalCases
            }
            int n = caseCalls.incrementAndGet();
            return n == 1 ? 2L : 3L; // 第一次非空 = PENDING(2)，第二次 = CONFIRMED(3)
        });
        when(conflictMapper.selectCount(any())).thenReturn(1L);
        when(hitLogMapper.selectCount(any())).thenReturn(7L);

        KnowledgeStatsResponse r = service.stats();
        assertThat(r.getTotalDocs()).isEqualTo(10L);
        assertThat(r.getPendingDocs()).isEqualTo(3L);
        assertThat(r.getExpiredDocs()).isEqualTo(2L);
        assertThat(r.getZeroHitDocs()).isEqualTo(4L);
        assertThat(r.getOrphanDocs()).isEqualTo(1L);
        assertThat(r.getTotalCases()).isEqualTo(5L);
        assertThat(r.getPendingCases()).isEqualTo(2L);
        assertThat(r.getConfirmedCases()).isEqualTo(3L);
        assertThat(r.getPendingConflicts()).isEqualTo(1L);
        assertThat(r.getMonthHits()).isEqualTo(7L);
    }

    @Test
    @DisplayName("总览统计：空知识库全为 0")
    void stats_empty() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(caseMapper.selectCount(any())).thenReturn(0L);
        when(conflictMapper.selectCount(any())).thenReturn(0L);
        when(hitLogMapper.selectCount(any())).thenReturn(0L);

        KnowledgeStatsResponse r = service.stats();
        assertThat(r.getTotalDocs()).isZero();
        assertThat(r.getMonthHits()).isZero();
        assertThat(r.getConfirmedCases()).isZero();
    }

    @Test
    @DisplayName("覆盖度：服务文档数与已审核数统计正确，计算服务级覆盖率")
    void coverage_withServices() {
        when(serviceMapper.selectList(any())).thenReturn(List.of(service("todo-service"), service("order-service")));
        when(documentMapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            String sql = w.getTargetSql();
            return sql != null && sql.contains("trust_status") ? 1L : 2L;
        });

        KnowledgeCoverageResponse r = service.coverage();
        assertThat(r.getTotalServices()).isEqualTo(2L);
        assertThat(r.getCoveredServices()).isEqualTo(2L);
        assertThat(r.getCoverageRate()).isEqualTo(1.0);
        assertThat(r.getDetails()).hasSize(2);
        KnowledgeCoverageResponse.ServiceCoverageItem first = r.getDetails().get(0);
        assertThat(first.getService()).isEqualTo("todo-service");
        assertThat(first.getDocCount()).isEqualTo(2L);
        assertThat(first.getVerifiedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("覆盖度：无服务返回空明细与 0 覆盖率")
    void coverage_noServices() {
        when(serviceMapper.selectList(any())).thenReturn(List.of());
        KnowledgeCoverageResponse r = service.coverage();
        assertThat(r.getTotalServices()).isZero();
        assertThat(r.getCoveredServices()).isZero();
        assertThat(r.getCoverageRate()).isEqualTo(0.0);
        assertThat(r.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("覆盖度：空名/未命名服务跳过，不参与覆盖统计")
    void coverage_skipBlankName() {
        when(serviceMapper.selectList(any())).thenReturn(List.of(service("todo-service"), service(null), service("  ")));
        when(documentMapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            String sql = w.getTargetSql();
            return sql != null && sql.contains("trust_status") ? 0L : 3L;
        });

        KnowledgeCoverageResponse r = service.coverage();
        assertThat(r.getTotalServices()).isEqualTo(3L);
        assertThat(r.getCoveredServices()).isEqualTo(1L);
        assertThat(r.getCoverageRate()).isEqualTo(0.33);
        assertThat(r.getDetails()).hasSize(1);
    }

    @Test
    @DisplayName("待办聚合：四类目全部返回")
    void todos_populated() {
        KnowledgeDocumentEntity pendingDoc = new KnowledgeDocumentEntity();
        pendingDoc.setId(1L);
        pendingDoc.setTitle("待审核文档");
        pendingDoc.setServiceIds("[\"todo-service\"]");
        pendingDoc.setTrustStatus("PENDING");
        pendingDoc.setCreatedAt(LocalDateTime.of(2026, 10, 1, 10, 0));

        KnowledgeDocumentEntity expiredDoc = new KnowledgeDocumentEntity();
        expiredDoc.setId(2L);
        expiredDoc.setTitle("已超期文档");
        expiredDoc.setReviewAt(LocalDateTime.of(2026, 9, 1, 0, 0));
        expiredDoc.setCreatedAt(LocalDateTime.of(2026, 8, 1, 0, 0));

        KnowledgeCaseEntity pendingCase = new KnowledgeCaseEntity();
        pendingCase.setId(3L);
        pendingCase.setTitle("待沉淀案例");
        pendingCase.setService("todo-service");
        pendingCase.setStatus("PENDING");
        pendingCase.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));

        KnowledgeConflictEntity conflict = new KnowledgeConflictEntity();
        conflict.setId(4L);
        conflict.setDocId(1L);
        conflict.setMatchedDocId(2L);
        conflict.setStatus("PENDING");
        conflict.setCreatedAt(LocalDateTime.of(2026, 10, 3, 8, 0));

        when(documentMapper.selectList(any())).thenReturn(List.of(pendingDoc), List.of(expiredDoc));
        when(caseMapper.selectList(any())).thenReturn(List.of(pendingCase));
        when(conflictMapper.selectList(any())).thenReturn(List.of(conflict));

        KnowledgeTodoResponse r = service.todos();
        assertThat(r.getPendingDocs()).hasSize(1);
        assertThat(r.getPendingDocs().get(0).getService()).isEqualTo("todo-service");
        assertThat(r.getPendingDocs().get(0).getItemType()).isEqualTo("DOCUMENT");
        assertThat(r.getExpiredDocs()).hasSize(1);
        assertThat(r.getExpiredDocs().get(0).getStatus()).isEqualTo("EXPIRED");
        assertThat(r.getExpiredDocs().get(0).getDueAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(r.getPendingCases()).hasSize(1);
        assertThat(r.getPendingCases().get(0).getItemType()).isEqualTo("CASE");
        assertThat(r.getConflicts()).hasSize(1);
        assertThat(r.getConflicts().get(0).getItemType()).isEqualTo("CONFLICT");
        assertThat(r.getConflicts().get(0).getTitle()).contains("文档#1 与 #2 冲突");
    }

    @Test
    @DisplayName("待办聚合：全空时四类目为空列表")
    void todos_empty() {
        when(documentMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(caseMapper.selectList(any())).thenReturn(List.of());
        when(conflictMapper.selectList(any())).thenReturn(List.of());

        KnowledgeTodoResponse r = service.todos();
        assertThat(r.getPendingDocs()).isEmpty();
        assertThat(r.getExpiredDocs()).isEmpty();
        assertThat(r.getPendingCases()).isEmpty();
        assertThat(r.getConflicts()).isEmpty();
    }

    @Test
    @DisplayName("待办聚合：service_ids 解析失败时 service 为 null，不影响返回")
    void todos_serviceIdsParseFailure() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(1L);
        doc.setTitle("坏 JSON");
        doc.setServiceIds("not-json");
        when(documentMapper.selectList(any())).thenReturn(List.of(doc), List.of());
        when(caseMapper.selectList(any())).thenReturn(List.of());
        when(conflictMapper.selectList(any())).thenReturn(List.of());

        KnowledgeTodoResponse r = service.todos();
        assertThat(r.getPendingDocs()).hasSize(1);
        assertThat(r.getPendingDocs().get(0).getService()).isNull();
    }

    // ==================== P2-③ 健康度明细 ====================

    @Test
    @DisplayName("health：返回超期/零命中/孤儿三类明细")
    void health_populated() {
        KnowledgeDocumentEntity expired = new KnowledgeDocumentEntity();
        expired.setId(1L);
        expired.setTitle("过期手册");
        expired.setTrustStatus("VERIFIED");
        expired.setHitCount(2L);
        expired.setReviewAt(java.time.LocalDateTime.now().minusDays(1));

        KnowledgeDocumentEntity zeroHit = new KnowledgeDocumentEntity();
        zeroHit.setId(2L);
        zeroHit.setTitle("无人引用");
        zeroHit.setTrustStatus("VERIFIED");
        zeroHit.setHitCount(0L);

        KnowledgeDocumentEntity orphan = new KnowledgeDocumentEntity();
        orphan.setId(3L);
        orphan.setTitle("未入库文档");
        orphan.setTrustStatus("PENDING");

        // 按调用顺序：expired → zeroHit → orphan
        when(documentMapper.selectList(any())).thenReturn(List.of(expired), List.of(zeroHit), List.of(orphan));

        KnowledgeHealthResponse r = service.health();

        assertThat(r.getExpired()).hasSize(1);
        assertThat(r.getExpired().get(0).getId()).isEqualTo(1L);
        assertThat(r.getExpired().get(0).getTitle()).isEqualTo("过期手册");
        assertThat(r.getZeroHit()).hasSize(1);
        assertThat(r.getZeroHit().get(0).getHitCount()).isZero();
        assertThat(r.getOrphan()).hasSize(1);
        assertThat(r.getOrphan().get(0).getTrustStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("health：无问题文档时三类均返回空列表")
    void health_empty() {
        when(documentMapper.selectList(any())).thenReturn(List.of(), List.of(), List.of());

        KnowledgeHealthResponse r = service.health();

        assertThat(r.getExpired()).isEmpty();
        assertThat(r.getZeroHit()).isEmpty();
        assertThat(r.getOrphan()).isEmpty();
    }
}
