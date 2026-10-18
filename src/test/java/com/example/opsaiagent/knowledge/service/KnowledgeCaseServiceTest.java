package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.mapper.DiagnosisRecordMapper;
import com.example.opsaiagent.dto.PageResult;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.CaseReviseRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseDetailResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseSummaryResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.mapper.FailureSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障案例服务单元测试
 * 覆盖：列表（筛选/分页边界）、详情（五步链路/快照解析/不存在）、
 * 确认沉淀（状态流转/兜底根因/知识文档生成/向量化/非 PENDING）、
 * 修正沉淀（修正字段/脱敏校验）、丢弃（状态流转/非 PENDING）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeCaseServiceTest {

    @Mock
    private KnowledgeCaseMapper caseMapper;
    @Mock
    private DiagnosisRecordMapper diagnosisRecordMapper;
    @Mock
    private AlertEventMapper alertEventMapper;
    @Mock
    private FailureSnapshotMapper failureSnapshotMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private VectorStore vectorStore;

    private KnowledgeCaseService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new KnowledgeCaseService(caseMapper, diagnosisRecordMapper, alertEventMapper,
                failureSnapshotMapper, documentMapper, chunkMapper, vectorStore, objectMapper);
    }

    private KnowledgeCaseEntity pendingCase() {
        KnowledgeCaseEntity e = new KnowledgeCaseEntity();
        e.setId(1L);
        e.setSourceAlertId(100L);
        e.setDiagnosisRecordId(200L);
        e.setService("order-service");
        e.setTitle("数据库连接池耗尽导致接口超时");
        e.setSymptom("接口 RT 飙升至 3000ms，连接池耗尽");
        e.setRootCause("数据库连接数达到上限，未及时释放");
        e.setEvidence("[\"活跃线程 200/200\",\"DB 连接数 80/50\"]");
        e.setEffectiveAction("重启数据库连接池并扩容");
        e.setConfidence(85);
        e.setMaskedStatus("NONE");
        e.setStatus("PENDING");
        e.setDurationMin(25);
        e.setCreatedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        return e;
    }

    private DiagnosisRecordEntity diagnosis() {
        DiagnosisRecordEntity d = new DiagnosisRecordEntity();
        d.setId(200L);
        d.setSnapshotId(300L);
        d.setAlertEventId(100L);
        d.setRuleName("接口超时");
        d.setService("order-service");
        d.setMetric("http_rt");
        d.setRootCause("数据库连接数达到上限，未及时释放");
        d.setConfidence(85);
        d.setCreatedAt(LocalDateTime.of(2026, 1, 2, 10, 1));
        return d;
    }

    /** 构造主诊断记录（status=SUCCESS，非追问） */
    private DiagnosisRecordEntity diag(Long id, String service, String rootCause, String summary, int confidence) {
        DiagnosisRecordEntity d = new DiagnosisRecordEntity();
        d.setId(id);
        d.setStatus("SUCCESS");
        d.setAlertEventId(100L);
        d.setService(service);
        d.setRootCause(rootCause);
        d.setSummary(summary);
        d.setConfidence(confidence);
        d.setCreatedAt(LocalDateTime.now());
        return d;
    }

    private AlertEventEntity alert() {
        AlertEventEntity a = new AlertEventEntity();
        a.setId(100L);
        a.setRuleId(1L);
        a.setRuleName("接口超时");
        a.setService("order-service");
        a.setMetric("http_rt");
        a.setThreshold(1000.0);
        a.setCurrentValue(3200.0);
        a.setStatus("RESOLVED");
        a.setFiredAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        a.setResolvedAt(LocalDateTime.of(2026, 1, 2, 10, 30));
        return a;
    }

    private FailureSnapshotEntity snapshot() {
        FailureSnapshotEntity s = new FailureSnapshotEntity();
        s.setId(300L);
        s.setAlertEventId(100L);
        s.setRuleName("接口超时");
        s.setService("order-service");
        s.setSnapshotJson("{\"serviceStatus\":{\"status\":\"DOWN\"},\"metricWindow\":[1,2,3]}");
        s.setCreatedAt(LocalDateTime.of(2026, 1, 2, 10, 0, 30));
        return s;
    }

    @Nested
    @DisplayName("列表查询")
    class ListTests {

        @Test
        @DisplayName("无筛选：默认全量分页")
        void list_noFilter() {
            Page<KnowledgeCaseEntity> page = new Page<>(1, 10);
            page.setRecords(List.of(pendingCase()));
            page.setTotal(1);
            when(caseMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

            PageResult<KnowledgeCaseSummaryResponse> r = service.list(null, 1, 10);

            assertThat(r.getTotal()).isEqualTo(1);
            assertThat(r.getRecords()).hasSize(1);
            assertThat(r.getRecords().get(0).getId()).isEqualTo(1L);
            assertThat(r.getRecords().get(0).getStatus()).isEqualTo("PENDING");
            assertThat(r.getRecords().get(0).getTitle()).isEqualTo("数据库连接池耗尽导致接口超时");
        }

        @Test
        @DisplayName("按状态筛选：PENDING")
        void list_byStatus() {
            when(caseMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(new Page<>(1, 10));

            service.list("PENDING", 1, 10);

            verify(caseMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        }

        @Test
        @DisplayName("分页边界收敛：page<1 → 1，size>100 → 100，size<1 → 1")
        void list_pageBounds() {
            when(caseMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(new Page<>(1, 100));

            PageResult<KnowledgeCaseSummaryResponse> r = service.list(null, 0, 999);

            assertThat(r.getPage()).isEqualTo(1);
            assertThat(r.getSize()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("案例详情")
    class DetailTests {

        @Test
        @DisplayName("存在且链路完整：五步 ALERT/SNAPSHOT/DIAGNOSIS/ACTION/RECOVERY")
        void getById_fullFlow() {
            when(caseMapper.selectById(1L)).thenReturn(pendingCase());
            when(diagnosisRecordMapper.selectById(200L)).thenReturn(diagnosis());
            when(alertEventMapper.selectById(100L)).thenReturn(alert());
            when(failureSnapshotMapper.selectById(300L)).thenReturn(snapshot());

            KnowledgeCaseDetailResponse r = service.getById(1L);

            assertThat(r.getId()).isEqualTo(1L);
            assertThat(r.getRootCause()).isEqualTo("数据库连接数达到上限，未及时释放");
            assertThat(r.getEvidence()).containsExactly("活跃线程 200/200", "DB 连接数 80/50");
            assertThat(r.getFlow()).hasSize(5);
            assertThat(r.getFlow()).extracting("step")
                    .containsExactly("ALERT", "SNAPSHOT", "DIAGNOSIS", "ACTION", "RECOVERY");
            assertThat(r.getFlow().get(0).getContent()).contains("3200.0");
            assertThat(r.getFlow().get(1).getContent()).contains("DOWN").contains("3 条");
            assertThat(r.getFlow().get(3).getContent()).isEqualTo("重启数据库连接池并扩容");
            assertThat(r.getFlow().get(4).getContent()).contains("已恢复");
        }

        @Test
        @DisplayName("诊断/告警/快照全部缺失：仅处置步")
        void getById_minimal() {
            KnowledgeCaseEntity e = pendingCase();
            e.setDiagnosisRecordId(null);
            e.setSourceAlertId(null);
            when(caseMapper.selectById(1L)).thenReturn(e);

            KnowledgeCaseDetailResponse r = service.getById(1L);

            assertThat(r.getFlow()).hasSize(1);
            assertThat(r.getFlow().get(0).getStep()).isEqualTo("ACTION");
        }

        @Test
        @DisplayName("快照 JSON 解析失败：返回截断原文")
        void getById_snapshotInvalidJson() {
            when(caseMapper.selectById(1L)).thenReturn(pendingCase());
            when(diagnosisRecordMapper.selectById(200L)).thenReturn(diagnosis());
            when(alertEventMapper.selectById(100L)).thenReturn(alert());
            FailureSnapshotEntity bad = new FailureSnapshotEntity();
            bad.setId(300L);
            bad.setSnapshotJson("x".repeat(250));
            when(failureSnapshotMapper.selectById(300L)).thenReturn(bad);

            KnowledgeCaseDetailResponse r = service.getById(1L);

            assertThat(r.getFlow()).hasSize(5);
            assertThat(r.getFlow().get(1).getContent()).startsWith("xxx").endsWith("…");
        }

        @Test
        @DisplayName("不存在：KNOWLEDGE_CASE_NOT_FOUND")
        void getById_notFound() {
            when(caseMapper.selectById(9L)).thenReturn(null);

            assertThatThrownBy(() -> service.getById(9L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("故障案例不存在");
        }
    }

    /** 长内容待确认案例，保证 TokenTextSplitter 至少产出一个 chunk */
    private KnowledgeCaseEntity longPendingCase() {
        KnowledgeCaseEntity e = pendingCase();
        e.setSymptom("接口 RT 飙升至 3000ms，连接池耗尽，错误率 5%，持续 10 分钟。"
                + "详细现象说明".repeat(30));
        e.setRootCause("数据库连接数达到上限，未及时释放。根因分析详细内容".repeat(30));
        e.setEffectiveAction("重启数据库连接池并扩容，同时优化连接释放逻辑。处置详细说明".repeat(20));
        return e;
    }

    @Nested
    @DisplayName("确认沉淀")
    class ConfirmTests {

        @Test
        @DisplayName("成功：状态→CONFIRMED、根因兜底、生成 CASE 文档并向量化")
        void confirm_ok() {
            KnowledgeCaseEntity e = longPendingCase();
            e.setRealRootCause(null);
            when(caseMapper.selectById(1L)).thenReturn(e);
            when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenReturn(1);
            when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);

            KnowledgeCaseDetailResponse r = service.confirm(1L);

            assertThat(e.getStatus()).isEqualTo("CONFIRMED");
            assertThat(e.getRealRootCause()).isEqualTo(e.getRootCause());
            assertThat(e.getConfirmedBy()).isEqualTo("system");
            assertThat(e.getClosedLoopAt()).isNotNull();
            assertThat(e.getMaskedStatus()).isEqualTo("NONE");
            verify(caseMapper).updateById(e);
            verify(documentMapper).insert(any(KnowledgeDocumentEntity.class));
            verify(chunkMapper, org.mockito.Mockito.atLeastOnce()).insert(any(KnowledgeChunkEntity.class));
            verify(vectorStore).add(anyList());
        }

        @Test
        @DisplayName("已有真实根因：确认时不覆盖")
        void confirm_keepRealRootCause() {
            KnowledgeCaseEntity e = longPendingCase();
            e.setRealRootCause("人工确认的根因");
            when(caseMapper.selectById(1L)).thenReturn(e);
            when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenReturn(1);
            when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);

            service.confirm(1L);

            assertThat(e.getRealRootCause()).isEqualTo("人工确认的根因");
        }

        @Test
        @DisplayName("案例内容为空：状态仍确认但不沉淀文档")
        void confirm_emptyContent() {
            KnowledgeCaseEntity e = pendingCase();
            e.setTitle("");
            e.setSymptom("");
            e.setRootCause("");
            e.setRealRootCause("");
            e.setEffectiveAction("");
            when(caseMapper.selectById(1L)).thenReturn(e);

            KnowledgeCaseDetailResponse r = service.confirm(1L);

            assertThat(e.getStatus()).isEqualTo("CONFIRMED");
            verify(documentMapper, never()).insert(any(KnowledgeDocumentEntity.class));
            verify(vectorStore, never()).add(anyList());
        }

        @Test
        @DisplayName("非 PENDING：KNOWLEDGE_CASE_INVALID")
        void confirm_notPending() {
            KnowledgeCaseEntity e = pendingCase();
            e.setStatus("CONFIRMED");
            when(caseMapper.selectById(1L)).thenReturn(e);

            assertThatThrownBy(() -> service.confirm(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅待确认案例可操作");
            verify(documentMapper, never()).insert(any(KnowledgeDocumentEntity.class));
            verify(vectorStore, never()).add(anyList());
        }

        @Test
        @DisplayName("不存在：KNOWLEDGE_CASE_NOT_FOUND")
        void confirm_notFound() {
            when(caseMapper.selectById(1L)).thenReturn(null);

            assertThatThrownBy(() -> service.confirm(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("故障案例不存在");
        }
    }

    @Nested
    @DisplayName("修正沉淀")
    class ReviseTests {

        @Test
        @DisplayName("修正根因/处置/脱敏后确认沉淀")
        void revise_ok() {
            KnowledgeCaseEntity e = longPendingCase();
            when(caseMapper.selectById(1L)).thenReturn(e);
            when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenReturn(1);
            when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);

            CaseReviseRequest req = new CaseReviseRequest();
            req.setRealRootCause("修正后的根因");
            req.setEffectiveAction("修正后的处置");
            req.setMaskedStatus("PARTIAL");

            KnowledgeCaseDetailResponse r = service.revise(1L, req);

            assertThat(e.getRealRootCause()).isEqualTo("修正后的根因");
            assertThat(e.getEffectiveAction()).isEqualTo("修正后的处置");
            assertThat(e.getMaskedStatus()).isEqualTo("PARTIAL");
            assertThat(e.getStatus()).isEqualTo("CONFIRMED");
            verify(vectorStore).add(anyList());
        }

        @Test
        @DisplayName("不传修正字段：仅确认沉淀")
        void revise_noChanges() {
            KnowledgeCaseEntity e = pendingCase();
            when(caseMapper.selectById(1L)).thenReturn(e);
            when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenReturn(1);
            when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);

            service.revise(1L, new CaseReviseRequest());

            assertThat(e.getStatus()).isEqualTo("CONFIRMED");
            assertThat(e.getRealRootCause()).isEqualTo("数据库连接数达到上限，未及时释放");
        }

        @Test
        @DisplayName("无效脱敏状态：KNOWLEDGE_CASE_INVALID")
        void revise_invalidMaskedStatus() {
            KnowledgeCaseEntity e = pendingCase();
            when(caseMapper.selectById(1L)).thenReturn(e);
            CaseReviseRequest req = new CaseReviseRequest();
            req.setMaskedStatus("XXX");

            assertThatThrownBy(() -> service.revise(1L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无效的脱敏状态");
            verify(vectorStore, never()).add(anyList());
        }

        @Test
        @DisplayName("非 PENDING：KNOWLEDGE_CASE_INVALID")
        void revise_notPending() {
            KnowledgeCaseEntity e = pendingCase();
            e.setStatus("DISCARDED");
            when(caseMapper.selectById(1L)).thenReturn(e);

            assertThatThrownBy(() -> service.revise(1L, new CaseReviseRequest()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅待确认案例可操作");
        }
    }

    @Nested
    @DisplayName("丢弃候选")
    class DiscardTests {

        @Test
        @DisplayName("成功：状态→DISCARDED")
        void discard_ok() {
            KnowledgeCaseEntity e = pendingCase();
            when(caseMapper.selectById(1L)).thenReturn(e);

            service.discard(1L);

            assertThat(e.getStatus()).isEqualTo("DISCARDED");
            verify(caseMapper).updateById(e);
        }

        @Test
        @DisplayName("非 PENDING：KNOWLEDGE_CASE_INVALID")
        void discard_notPending() {
            KnowledgeCaseEntity e = pendingCase();
            e.setStatus("CONFIRMED");
            when(caseMapper.selectById(1L)).thenReturn(e);

            assertThatThrownBy(() -> service.discard(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅待确认案例可操作");
        }

        @Test
        @DisplayName("不存在：KNOWLEDGE_CASE_NOT_FOUND")
        void discard_notFound() {
            when(caseMapper.selectById(1L)).thenReturn(null);

            assertThatThrownBy(() -> service.discard(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("故障案例不存在");
        }
    }

    @Nested
    @DisplayName("P1-⑤ 案例自动生成")
    class GenerateCandidatesTests {

        @Test
        @DisplayName("成功：从成功主诊断记录生成待确认候选并去重")
        void generate_newCandidates() {
            DiagnosisRecordEntity d1 = diag(1L, "todo-service", "连接池耗尽", "连接池打满", 85);
            DiagnosisRecordEntity d2 = diag(2L, "todo-service", "磁盘满", null, 90);
            when(diagnosisRecordMapper.selectList(any())).thenReturn(List.of(d1, d2));
            when(caseMapper.selectCount(any())).thenReturn(0L);

            int generated = service.generateCandidates(10);

            assertThat(generated).isEqualTo(2);
            ArgumentCaptor<KnowledgeCaseEntity> captor = ArgumentCaptor.forClass(KnowledgeCaseEntity.class);
            verify(caseMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
            KnowledgeCaseEntity first = captor.getAllValues().get(0);
            assertThat(first.getDiagnosisRecordId()).isEqualTo(1L);
            assertThat(first.getSourceAlertId()).isEqualTo(100L);
            assertThat(first.getTitle()).contains("[todo-service]").contains("连接池耗尽");
            assertThat(first.getSymptom()).isEqualTo("连接池打满");
            assertThat(first.getRootCause()).isEqualTo("连接池耗尽");
            assertThat(first.getConfidence()).isEqualTo(85);
            assertThat(first.getStatus()).isEqualTo("PENDING");
            assertThat(first.getMaskedStatus()).isEqualTo("NONE");
            assertThat(first.getCitedCount()).isZero();
        }

        @Test
        @DisplayName("已存在候选跳过：诊断记录已生成过案例不重复")
        void generate_skipExisting() {
            DiagnosisRecordEntity d1 = diag(1L, "todo-service", "连接池耗尽", "连接池打满", 85);
            when(diagnosisRecordMapper.selectList(any())).thenReturn(List.of(d1));
            when(caseMapper.selectCount(any())).thenReturn(1L);

            int generated = service.generateCandidates(10);

            assertThat(generated).isZero();
            verify(caseMapper, never()).insert(any());
        }

        @Test
        @DisplayName("诊断记录为空：返回 0 且不插入")
        void generate_noRecords() {
            when(diagnosisRecordMapper.selectList(any())).thenReturn(List.of());
            assertThat(service.generateCandidates(20)).isZero();
            verify(caseMapper, never()).insert(any());
        }

        @Test
        @DisplayName("标题/症状兜底：无根因用摘要，无摘要用规则名+指标")
        void generate_fallbackFields() {
            DiagnosisRecordEntity d1 = new DiagnosisRecordEntity();
            d1.setId(3L);
            d1.setStatus("SUCCESS");
            d1.setService("cache-service");
            d1.setRuleName("缓存命中率过低");
            d1.setMetric("hit_rate");
            d1.setRootCause(null);
            d1.setSummary(null);
            when(diagnosisRecordMapper.selectList(any())).thenReturn(List.of(d1));
            when(caseMapper.selectCount(any())).thenReturn(0L);

            service.generateCandidates(10);

            ArgumentCaptor<KnowledgeCaseEntity> captor = ArgumentCaptor.forClass(KnowledgeCaseEntity.class);
            verify(caseMapper).insert(captor.capture());
            assertThat(captor.getValue().getTitle()).contains("诊断记录");
            assertThat(captor.getValue().getSymptom()).isEqualTo("缓存命中率过低：hit_rate");
        }
    }
}
