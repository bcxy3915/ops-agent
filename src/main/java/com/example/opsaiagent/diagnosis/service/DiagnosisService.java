package com.example.opsaiagent.diagnosis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.diagnosis.dto.DiagnosisResult;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.mapper.DiagnosisRecordMapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.service.FailureSnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * LLM 智能诊断服务（T15）
 * 链路：故障快照 → 组装诊断提示词 → LLM 结构化输出（根因/证据/置信度/建议）→ 落库
 * 支持基于已有诊断的追问（question/answer 落在新记录上）。
 * LLM 调用失败时记录 FAILED 状态，不向上抛异常（供 T18 闭环降级使用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisService {

    private final DiagnosisRecordMapper diagnosisMapper;
    private final FailureSnapshotService snapshotService;
    private final DiagnosisPromptTemplate promptTemplate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final HybridRetriever hybridRetriever;

    /**
     * 基于故障快照执行一次 LLM 诊断并落库
     * @param snapshotId 故障快照 ID
     * @return 诊断记录（LLM 失败时 status=FAILED，errorMessage 填充原因）
     */
    public DiagnosisRecordEntity diagnoseBySnapshot(Long snapshotId) {
        FailureSnapshotEntity snapshot = snapshotService.getById(snapshotId);
        return diagnose(snapshot);
    }

    /**
     * 内部诊断执行：取快照 JSON → 组装提示词 → LLM 结构化输出 → 落库
     */
    public DiagnosisRecordEntity diagnose(FailureSnapshotEntity snapshot) {
        DiagnosisRecordEntity record = new DiagnosisRecordEntity();
        record.setAlertEventId(snapshot.getAlertEventId());
        record.setSnapshotId(snapshot.getId());
        record.setRuleId(snapshot.getRuleId());
        record.setRuleName(snapshot.getRuleName());
        record.setService(snapshot.getService());
        record.setMetric(snapshot.getMetric());
        record.setStatus("SUCCESS");
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        try {
            DiagnosisResult result = callLlm(snapshot.getSnapshotJson(), null);
            applyResult(record, result);
        } catch (Exception e) {
            log.warn("LLM 诊断失败 snapshotId={}: {}", snapshot.getId(), e.getMessage());
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        diagnosisMapper.insert(record);
        return record;
    }

    /**
     * 基于已有诊断记录追问：把快照 + 原诊断 + 新问题交给 LLM，回答落在新记录
     * @param recordId 已有诊断记录 ID
     * @param question 追问问题
     * @return 追问记录（question/answer 非空，继承事件与快照关联）
     */
    public DiagnosisRecordEntity followUp(Long recordId, String question) {
        DiagnosisRecordEntity base = getById(recordId);
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "追问问题不能为空");
        }
        FailureSnapshotEntity snapshot = base.getSnapshotId() == null
                ? null : snapshotService.getById(base.getSnapshotId());

        DiagnosisRecordEntity record = new DiagnosisRecordEntity();
        record.setAlertEventId(base.getAlertEventId());
        record.setSnapshotId(base.getSnapshotId());
        record.setRuleId(base.getRuleId());
        record.setRuleName(base.getRuleName());
        record.setService(base.getService());
        record.setMetric(base.getMetric());
        record.setStatus("SUCCESS");
        record.setQuestion(question);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        try {
            String snapshotJson = snapshot == null ? null : snapshot.getSnapshotJson();
            String answer = callFollowUpLlm(snapshotJson, base, question);
            record.setAnswer(answer);
        } catch (Exception e) {
            log.warn("LLM 追问失败 recordId={}: {}", recordId, e.getMessage());
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        diagnosisMapper.insert(record);
        return record;
    }

    /**
     * 按 ID 查询诊断记录
     */
    public DiagnosisRecordEntity getById(Long id) {
        DiagnosisRecordEntity entity = diagnosisMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND, "诊断记录不存在: " + id);
        }
        return entity;
    }

    /**
     * 按服务 / 告警事件 / 规则查询诊断记录（默认最新优先）
     */
    public List<DiagnosisRecordEntity> list(String service, Long alertEventId, Long ruleId, int limit) {
        LambdaQueryWrapper<DiagnosisRecordEntity> qw = new LambdaQueryWrapper<>();
        if (service != null && !service.isBlank()) {
            qw.eq(DiagnosisRecordEntity::getService, service);
        }
        if (alertEventId != null) {
            qw.eq(DiagnosisRecordEntity::getAlertEventId, alertEventId);
        }
        if (ruleId != null) {
            qw.eq(DiagnosisRecordEntity::getRuleId, ruleId);
        }
        qw.orderByDesc(DiagnosisRecordEntity::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        return diagnosisMapper.selectList(qw);
    }

    // ==================== LLM 调用（可测试点） ====================

    /**
     * 结构化诊断调用：系统提示词 + 快照 JSON + 知识库上下文，约束 JSON Schema 输出 DiagnosisResult
     */
    protected DiagnosisResult callLlm(String snapshotJson, String context) {
        String userMessage = promptTemplate.renderUserMessage(snapshotJson);
        String kbContext = context == null ? retrieveContext(snapshotJson) : context;
        return chatClient.prompt()
                .system(buildSystemPrompt(kbContext))
                .user(userMessage)
                .call()
                .entity(DiagnosisResult.class);
    }

    /**
     * 知识库引用增强（T17）：以快照关键信息（服务/指标/状态/详情）作为检索词，
     * 从混合检索（向量+BM25+RRF+Rerank）召回相关运维知识片段，注入系统提示词作为参考。
     * 检索失败或为空时返回"无参考"，不阻断诊断。
     */
    protected String retrieveContext(String snapshotJson) {
        try {
            String query = buildSearchQuery(snapshotJson);
            List<Document> docs = hybridRetriever.retrieve(query, 3);
            if (docs.isEmpty()) {
                return "（知识库中未检索到相关参考，请仅基于快照事实诊断）";
            }
            return docs.stream()
                    .map(d -> "【来源：" + d.getMetadata().getOrDefault("source", "知识库") + "】\n"
                            + Objects.requireNonNullElse(d.getText(), ""))
                    .collect(Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            log.warn("知识库检索失败，降级为无参考: {}", e.getMessage());
            return "（知识库检索失败，请仅基于快照事实诊断）";
        }
    }

    /**
     * 从快照 JSON 提取检索词（服务 + 指标 + 状态 + 详情），尽量精炼
     */
    String buildSearchQuery(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return "系统故障诊断";
        }
        StringBuilder sb = new StringBuilder();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(snapshotJson);
            com.fasterxml.jackson.databind.JsonNode event = root.path("event");
            sb.append(event.path("service").asText(""));
            String metric = event.path("metric").asText("");
            if (!metric.isBlank()) {
                sb.append(" ").append(metric).append(" 指标异常");
            }
            String status = root.path("serviceStatus").path("status").asText("");
            if (!status.isBlank()) {
                sb.append(" 服务状态 ").append(status);
            }
            String detail = event.path("detail").asText("");
            if (!detail.isBlank()) {
                sb.append(" ").append(detail);
            }
        } catch (Exception ignored) {
        }
        return sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
    }

    private String buildSystemPrompt(String kbContext) {
        if (kbContext == null || kbContext.isBlank()) {
            return DiagnosisPromptTemplate.SYSTEM_PROMPT;
        }
        return DiagnosisPromptTemplate.SYSTEM_PROMPT + "\n\n【知识库参考（仅供背景，不可替代快照事实）】\n" + kbContext;
    }

    /**
     * 追问调用：系统提示词 + 快照 + 原诊断摘要 + 问题，返回文本回答
     */
    protected String callFollowUpLlm(String snapshotJson, DiagnosisRecordEntity base, String question) {
        StringBuilder user = new StringBuilder();
        if (snapshotJson != null && !snapshotJson.isBlank()) {
            user.append("故障快照：\n").append(snapshotJson).append("\n\n");
        }
        if (base.getRootCause() != null && !base.getRootCause().isBlank()) {
            user.append("已有诊断根因：").append(base.getRootCause()).append("\n");
        }
        if (base.getSummary() != null && !base.getSummary().isBlank()) {
            user.append("已有诊断摘要：").append(base.getSummary()).append("\n");
        }
        user.append("追问问题：").append(question);
        return chatClient.prompt()
                .system("你是一名资深的 SRE 运维专家，基于给定的故障快照与已有诊断回答用户的追问。直接输出答案，不要输出 JSON。")
                .user(user.toString())
                .call()
                .content();
    }

    private void applyResult(DiagnosisRecordEntity record, DiagnosisResult result) throws JsonProcessingException {
        record.setRootCause(result.getRootCause());
        record.setEvidence(objectMapper.writeValueAsString(result.getEvidence()));
        record.setConfidence(result.getConfidence());
        record.setSuggestions(objectMapper.writeValueAsString(result.getSuggestions()));
        record.setSummary(result.getSummary());
    }

    /**
     * 解析证据 JSON 数组（供 API 展示）
     */
    public List<String> parseEvidence(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(evidenceJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 解析建议 JSON 数组（供 API 展示）
     */
    public List<String> parseSuggestions(String suggestionsJson) {
        if (suggestionsJson == null || suggestionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(suggestionsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
