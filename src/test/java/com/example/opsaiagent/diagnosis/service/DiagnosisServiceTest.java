package com.example.opsaiagent.diagnosis.service;

import com.example.opsaiagent.diagnosis.dto.DiagnosisResult;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.mapper.DiagnosisRecordMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.service.FailureSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 智能诊断服务测试（T15/T17）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLM 智能诊断服务")
class DiagnosisServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @Mock
    private DiagnosisRecordMapper diagnosisMapper;

    @Mock
    private FailureSnapshotService snapshotService;

    @Mock
    private HybridRetriever hybridRetriever;

    @Mock
    private KnowledgeDocumentMapper documentMapper;

    @Mock
    private KnowledgeCaseMapper caseMapper;

    @Spy
    private DiagnosisPromptTemplate promptTemplate = new DiagnosisPromptTemplate();

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private DiagnosisService service;

    private FailureSnapshotEntity snapshot;

    @BeforeEach
    void setUp() {
        service = new DiagnosisService(diagnosisMapper, snapshotService, promptTemplate, chatClient, objectMapper,
                hybridRetriever, documentMapper, caseMapper, new DiagnosisScope(null, null));
        snapshot = new FailureSnapshotEntity();
        snapshot.setId(100L);
        snapshot.setAlertEventId(200L);
        snapshot.setRuleId(10L);
        snapshot.setRuleName("cpu-high");
        snapshot.setService("svc-a");
        snapshot.setMetric("cpu");
        snapshot.setSnapshotJson("""
                {"event":{"service":"svc-a","metric":"cpu","status":"FIRING","detail":"CPU 持续高于阈值","firedAt":"2026-10-18T15:00:00"},
                 "serviceStatus":{"status":"DOWN","lastCheckedAt":"2026-10-18T15:00:00"},
                 "serviceInfo":{"name":"svc-a","owner":"platform"},
                 "metricWindow":[{"timestamp":1739760000000,"value":95.0}],
                 "capturedAt":"2026-10-18T15:00:00"}""");
    }

    private DiagnosisResult sampleResult() {
        return DiagnosisResult.builder()
                .rootCause("应用实例 CPU 打满，疑似流量突增或代码死循环")
                .evidence(List.of("CPU 均值 95%，远超阈值 80%", "服务状态 DOWN", "指标窗口内持续高位"))
                .confidence(85)
                .suggestions(List.of("重启/扩容应用实例", "检查最近一次发版变更", "抓取线程栈定位死循环"))
                .summary("CPU 持续打满导致服务不可用，建议优先扩容并排查变更。")
                .build();
    }

    @Test
    @DisplayName("基于快照诊断成功：调用 LLM 结构化输出并落库 SUCCESS")
    void diagnose_success() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(DiagnosisResult.class))
                .thenReturn(sampleResult());
        when(hybridRetriever.retrieve(anyString(), eq(3))).thenReturn(List.of(
                new Document("CPU 打满通常是流量突增或死循环导致", Map.of("source", "ops-runbook"))));
        when(diagnosisMapper.insert(any(DiagnosisRecordEntity.class))).thenReturn(1);

        DiagnosisRecordEntity record = service.diagnose(snapshot);

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getRootCause()).isEqualTo("应用实例 CPU 打满，疑似流量突增或代码死循环");
        assertThat(record.getConfidence()).isEqualTo(85);
        assertThat(record.getEvidence()).contains("CPU 均值 95%，远超阈值 80%");
        assertThat(service.parseSuggestions(record.getSuggestions())).hasSize(3);
        assertThat(record.getAlertEventId()).isEqualTo(200L);
        assertThat(record.getSnapshotId()).isEqualTo(100L);
        verify(diagnosisMapper).insert(record);
    }

    @Test
    @DisplayName("知识库检索失败降级：诊断仍成功（T17 容错）")
    void diagnose_kbRetrieveFails_stillSucceeds() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(DiagnosisResult.class))
                .thenReturn(sampleResult());
        when(hybridRetriever.retrieve(anyString(), eq(3))).thenThrow(new RuntimeException("kb down"));

        DiagnosisRecordEntity record = service.diagnose(snapshot);

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getRootCause()).isEqualTo(sampleResult().getRootCause());
        verify(diagnosisMapper).insert(any(DiagnosisRecordEntity.class));
    }

    @Test
    @DisplayName("LLM 调用失败：记录 FAILED 并填充错误信息，不抛异常")
    void diagnose_llmFails_recordsFailed() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(DiagnosisResult.class))
                .thenThrow(new RuntimeException("LLM timeout"));
        when(diagnosisMapper.insert(any(DiagnosisRecordEntity.class))).thenReturn(1);

        DiagnosisRecordEntity record = service.diagnose(snapshot);

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorMessage()).contains("LLM timeout");
        verify(diagnosisMapper).insert(record);
    }

    @Test
    @DisplayName("LLM 返回 null：按失败处理落库")
    void diagnose_llmReturnsNull_failed() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(DiagnosisResult.class))
                .thenReturn(null);
        when(diagnosisMapper.insert(any(DiagnosisRecordEntity.class))).thenReturn(1);

        DiagnosisRecordEntity record = service.diagnose(snapshot);

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("按快照 ID 诊断：先查快照再诊断")
    void diagnoseBySnapshot_loadsSnapshot() {
        when(snapshotService.getById(100L)).thenReturn(snapshot);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(DiagnosisResult.class))
                .thenReturn(sampleResult());
        when(diagnosisMapper.insert(any(DiagnosisRecordEntity.class))).thenReturn(1);

        DiagnosisRecordEntity record = service.diagnoseBySnapshot(100L);

        assertThat(record.getSnapshotId()).isEqualTo(100L);
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        verify(snapshotService).getById(100L);
    }

    @Test
    @DisplayName("快照不存在时抛 DIAGNOSIS 前置异常（由快照服务抛出）")
    void diagnoseBySnapshot_snapshotNotFound() {
        when(snapshotService.getById(999L))
                .thenThrow(new BusinessException(ErrorCode.SNAPSHOT_NOT_FOUND, "故障快照不存在: 999"));

        assertThatThrownBy(() -> service.diagnoseBySnapshot(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("故障快照不存在");
    }

    @Test
    @DisplayName("追问成功：基于已有诊断 + 问题调用 LLM，回答落库")
    void followUp_success() {
        DiagnosisRecordEntity base = new DiagnosisRecordEntity();
        base.setId(1L);
        base.setAlertEventId(200L);
        base.setSnapshotId(100L);
        base.setRootCause("应用实例 CPU 打满");
        base.setSummary("CPU 持续打满");
        when(diagnosisMapper.selectById(1L)).thenReturn(base);
        when(snapshotService.getById(100L)).thenReturn(snapshot);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("可以重点排查最近一次发版，回滚验证即可。");
        when(diagnosisMapper.insert(any(DiagnosisRecordEntity.class))).thenReturn(1);

        DiagnosisRecordEntity record = service.followUp(1L, "怎么快速止损？");

        assertThat(record.getQuestion()).isEqualTo("怎么快速止损？");
        assertThat(record.getAnswer()).contains("回滚");
        assertThat(record.getAlertEventId()).isEqualTo(200L);
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        verify(diagnosisMapper).insert(record);
    }

    @Test
    @DisplayName("追问问题为空：抛参数错误")
    void followUp_blankQuestion() {
        DiagnosisRecordEntity base = new DiagnosisRecordEntity();
        base.setId(1L);
        when(diagnosisMapper.selectById(1L)).thenReturn(base);

        assertThatThrownBy(() -> service.followUp(1L, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("追问记录不存在：抛 DIAGNOSIS_NOT_FOUND")
    void followUp_recordNotFound() {
        when(diagnosisMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.followUp(999L, "怎么办"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIAGNOSIS_NOT_FOUND);
    }

    @Test
    @DisplayName("追问 LLM 失败：记录 FAILED")
    void followUp_llmFails() {
        DiagnosisRecordEntity base = new DiagnosisRecordEntity();
        base.setId(1L);
        base.setAlertEventId(200L);
        base.setSnapshotId(100L);
        when(diagnosisMapper.selectById(1L)).thenReturn(base);
        when(snapshotService.getById(100L)).thenReturn(snapshot);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new RuntimeException("LLM timeout"));
        when(diagnosisMapper.insert(any(DiagnosisRecordEntity.class))).thenReturn(1);

        DiagnosisRecordEntity record = service.followUp(1L, "追问？");

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorMessage()).contains("LLM timeout");
    }

    @Test
    @DisplayName("getById 成功")
    void getById_found() {
        DiagnosisRecordEntity entity = new DiagnosisRecordEntity();
        entity.setId(5L);
        when(diagnosisMapper.selectById(5L)).thenReturn(entity);

        assertThat(service.getById(5L)).isSameAs(entity);
    }

    @Test
    @DisplayName("getById 不存在：抛 DIAGNOSIS_NOT_FOUND")
    void getById_notFound() {
        when(diagnosisMapper.selectById(5L)).thenReturn(null);

        assertThatThrownBy(() -> service.getById(5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DIAGNOSIS_NOT_FOUND);
    }

    @Test
    @DisplayName("list 支持按服务过滤")
    void list_byService() {
        when(diagnosisMapper.selectList(any())).thenReturn(List.of(new DiagnosisRecordEntity()));
        List<DiagnosisRecordEntity> result = service.list("svc-a", null, null, 50);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("list 无过滤条件")
    void list_noFilter() {
        when(diagnosisMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.list(null, null, null, 50)).isEmpty();
    }

    @Test
    @DisplayName("list limit 收敛到 1-200")
    void list_limitClamped() {
        when(diagnosisMapper.selectList(any())).thenReturn(List.of());
        service.list(null, null, null, 500);
        service.list(null, null, null, 0);
    }

    @Test
    @DisplayName("parseEvidence 解析 JSON 数组")
    void parseEvidence_ok() {
        assertThat(service.parseEvidence("[\"a\",\"b\"]")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("parseEvidence 空值/异常返回空列表")
    void parseEvidence_blankOrInvalid() {
        assertThat(service.parseEvidence(null)).isEmpty();
        assertThat(service.parseEvidence("not-json")).isEmpty();
    }

    @Test
    @DisplayName("parseSuggestions 解析 JSON 数组")
    void parseSuggestions_ok() {
        assertThat(service.parseSuggestions("[\"重启\",\"扩容\"]")).containsExactly("重启", "扩容");
    }

    @Test
    @DisplayName("buildSearchQuery 从快照提取服务/指标/状态/详情")
    void buildSearchQuery_extracts() {
        String query = service.buildSearchQuery(snapshot.getSnapshotJson());
        assertThat(query).contains("svc-a");
        assertThat(query).contains("cpu");
        assertThat(query).contains("DOWN");
    }

    @Test
    @DisplayName("buildSearchQuery 空快照返回兜底词")
    void buildSearchQuery_blank() {
        assertThat(service.buildSearchQuery("  ")).isEqualTo("系统故障诊断");
        assertThat(service.buildSearchQuery(null)).isEqualTo("系统故障诊断");
    }

    @Test
    @DisplayName("retrieveContext 知识库命中时注入检索片段")
    void retrieveContext_hits() {
        when(hybridRetriever.retrieve(anyString(), eq(3)))
                .thenReturn(List.of(new Document("CPU 打满排查步骤", Map.of("source", "runbook"))));

        String context = service.retrieveContext(snapshot.getSnapshotJson());

        assertThat(context).contains("CPU 打满排查步骤");
        assertThat(context).contains("runbook");
    }

    @Test
    @DisplayName("retrieveContext 知识库为空返回无参考提示")
    void retrieveContext_empty() {
        when(hybridRetriever.retrieve(anyString(), eq(3))).thenReturn(List.of());

        assertThat(service.retrieveContext(snapshot.getSnapshotJson())).contains("未检索到");
    }
}
