package com.example.opsaiagent.diagnosis.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.diagnosis.dto.DiagnosisReferenceResponse;
import com.example.opsaiagent.diagnosis.dto.DiagnosisResult;
import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.StructuredOutputGateway;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.mapper.DiagnosisRecordMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.service.FailureSnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.ai.api.ChatGateway;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final ChatGateway chatGateway;
    private final StructuredOutputGateway structuredOutputGateway;
    private final ObjectMapper objectMapper;
    private final HybridRetriever hybridRetriever;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeCaseMapper caseMapper;
    private final DiagnosisScope diagnosisScope;

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
     * P1：记录诊断耗时（durationMs，供首页 avgDiagnoseMs）与引用来源（referencesJson，供溯源展示）。
     */
    public DiagnosisRecordEntity diagnose(FailureSnapshotEntity snapshot) {
        DiagnosisRecordEntity record = new DiagnosisRecordEntity();
        record.setAlertEventId(snapshot.getAlertEventId());
        record.setSnapshotId(snapshot.getId());
        record.setTenantId(snapshot.getTenantId());
        record.setDeptId(snapshot.getDeptId());
        record.setRuleId(snapshot.getRuleId());
        record.setRuleName(snapshot.getRuleName());
        record.setService(snapshot.getService());
        record.setMetric(snapshot.getMetric());
        record.setStatus("SUCCESS");
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        long start = System.currentTimeMillis();
        try {
            RetrievalScope retrievalScope = diagnosisScope.of(snapshot).retrievalScope();
            String query = buildSearchQuery(snapshot.getSnapshotJson());
            List<Document> documents;
            String context;
            try {
                documents = retrieve(query, 3, retrievalScope).stream()
                        .filter(d -> d != null && StringUtils.hasText(d.getText()))
                        .map(d -> new Document(d.getId(), d.getText(), d.getMetadata(), d.getScore())).toList();
                context = formatContext(documents);
            } catch (Exception retrievalFailure) {
                log.warn("知识库检索失败，诊断使用快照事实: {}", retrievalFailure.getMessage());
                documents = List.of();
                context = "（知识库检索失败，请仅基于快照事实诊断）";
            }
            DiagnosisResult result = callLlm(snapshot.getSnapshotJson(), context, retrievalScope);
            applyResult(record, result);
            // P1 溯源：把本次 RAG 检索命中（参考知识/历史案例）落库
            record.setReferencesJson(referencesFromDocuments(documents, query));
        } catch (Exception e) {
            log.warn("LLM 诊断失败 snapshotId={}: {}", snapshot.getId(), e.getMessage());
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        record.setDurationMs(System.currentTimeMillis() - start);
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
        record.setTenantId(base.getTenantId());
        record.setDeptId(base.getDeptId());
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
            if (!StringUtils.hasText(answer)) {
                throw new IllegalStateException("模型未返回诊断追问答案");
            }
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
        if (entity == null || !diagnosisScope.current().canAccess(entity)) {
            throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND, "诊断记录不存在: " + id);
        }
        return entity;
    }

    /**
     * 按服务 / 告警事件 / 规则查询诊断记录（默认最新优先）
     */
    public List<DiagnosisRecordEntity> list(String service, Long alertEventId, Long ruleId, int limit) {
        LambdaQueryWrapper<DiagnosisRecordEntity> qw = diagnosisScope.current().diagnoses();
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
     * 结构化诊断调用：提示词约定 JSON 字段，由网关严格解析，保存前校验业务字段。
     * @param snapshotJson 故障快照 JSON
     * @param context 已提供的知识上下文；为空时执行检索
     * @return 模型结构化结果，业务有效性由保存入口校验
     */
    protected DiagnosisResult callLlm(String snapshotJson, String context) {
        return callLlm(snapshotJson, context, null);
    }

    /**
     * 带检索范围的结构化诊断调用。
     * @param snapshotJson 故障快照 JSON
     * @param context 已提供的知识上下文；为空时执行检索
     * @param scope 服务端可信检索范围
     * @return 模型结构化结果，业务有效性由保存入口校验
     */
    protected DiagnosisResult callLlm(String snapshotJson, String context, RetrievalScope scope) {
        String userMessage = promptTemplate.renderUserMessage(snapshotJson);
        String kbContext = context == null ? retrieveContext(snapshotJson, scope) : context;
        return structuredOutputGateway.generate(new ChatRequest(
                "diagnosis-" + System.nanoTime(), userMessage, buildSystemPrompt(kbContext), null),
                DiagnosisResult.class);
    }

    /**
     * 知识库引用增强（T17）：以快照关键信息（服务/指标/状态/详情）作为检索词，
     * 从混合检索（向量+BM25+RRF+Rerank）召回相关运维知识片段，注入系统提示词作为参考。
     * 检索失败或为空时返回"无参考"，不阻断诊断。
     */
    protected String retrieveContext(String snapshotJson) {
        return retrieveContext(snapshotJson, null);
    }

    /**
     * 带检索范围的知识库引用增强。
     */
    protected String retrieveContext(String snapshotJson, RetrievalScope scope) {
        try {
            String query = buildSearchQuery(snapshotJson);
            List<Document> docs = retrieve(query, 3, scope);
            return formatContext(docs);
        } catch (Exception e) {
            log.warn("知识库检索失败，降级为无参考: {}", e.getMessage());
            return "（知识库检索失败，请仅基于快照事实诊断）";
        }
    }

    /**
     * 从本次唯一检索结果构造模型上下文。
     * @param documents 本轮检索快照
     * @return 模型参考文本
     */
    private String formatContext(List<Document> documents) {
        if (documents.isEmpty()) { return "（知识库中未检索到相关参考，请仅基于快照事实诊断）"; }
        return documents.stream().map(d -> "【来源：" + d.getMetadata().getOrDefault("source", "知识库")
                + "】\n" + Objects.requireNonNullElse(d.getText(), ""))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 从实际交给模型的文档生成引用，禁止重新检索。
     * @param documents 本轮模型参考文档
     * @param query 本轮检索词
     * @return 引用 JSON；引用元数据处理失败时为空数组
     */
    private String referencesFromDocuments(List<Document> documents, String query) {
        try {
            return objectMapper.writeValueAsString(documents.stream().map(doc -> toReference(doc, query))
                    .filter(Objects::nonNull).toList());
        } catch (Exception failure) {
            log.warn("诊断引用生成失败: {}", failure.getMessage());
            return "[]";
        }
    }

    /**
     * 有范围时走显式范围检索；无范围保持旧默认检索，兼容单测与系统任务。
     */
    private List<Document> retrieve(String query, int topK, RetrievalScope scope) {
        return scope == null
                ? hybridRetriever.retrieve(query, topK)
                : hybridRetriever.retrieve(query, topK, scope);
    }

    /**
     * 检索命中 Document 转结构化引用
     * 复用知识文档元数据（docId/title/layer/version/owner），案例层反查 citedCount。
     */
    private DiagnosisReferenceResponse toReference(Document doc, String query) {
        if (doc == null || !StringUtils.hasText(doc.getText())) {
            return null;
        }
        Map<String, Object> md = doc.getMetadata() == null ? Map.of() : doc.getMetadata();
        String docIdStr = String.valueOf(md.getOrDefault("docId", ""));
        String layer = str(md.get("layer"));
        String title = str(md.get("title"));
        if (!StringUtils.hasText(title)) {
            title = str(md.get("source"));
        }
        if (!StringUtils.hasText(title)) {
            title = "知识条目";
        }

        Long docId = parseLong(docIdStr);
        Long caseId = parseCaseIdFromSource(str(md.get("source")));
        String refType = refTypeOf(layer, caseId);
        Long refId = refType.equals("CASE") ? caseId : docId;

        DiagnosisReferenceResponse response = DiagnosisReferenceResponse.builder()
                .refType(refType)
                .refId(refId)
                .title(title)
                .section(str(md.get("section")))
                .snippet(snippet(doc.getText(), query))
                .score(scoreOf(md))
                .layer(layer)
                .version(versionText(md))
                .build();

        // 文档层：反查真实 owner/version；案例层：反查被引用次数
        if (docId != null && "CASE".equals(refType) && caseId != null) {
            KnowledgeCaseEntity kc = caseMapper.selectById(caseId);
            if (kc != null) {
                if (StringUtils.hasText(kc.getTitle())) {
                    response.setTitle(kc.getTitle());
                }
                response.setCitedCount(kc.getCitedCount() == null ? 0L : kc.getCitedCount().longValue());
            }
        } else if (docId != null) {
            KnowledgeDocumentEntity kd = documentMapper.selectById(docId);
            if (kd != null) {
                response.setAuthor(kd.getOwner());
                if (kd.getVersion() != null) {
                    response.setVersion("v" + kd.getVersion());
                }
            }
        }
        return response;
    }

    /** 引用类型：CASE 案例 / BUILTIN 内置底座 / 其他为私有知识文档 */
    private String refTypeOf(String layer, Long caseId) {
        if (caseId != null || "CASE".equalsIgnoreCase(layer)) {
            return "CASE";
        }
        if ("BUILTIN".equalsIgnoreCase(layer)) {
            return "BASE_DOC";
        }
        return "KNOWLEDGE_DOC";
    }

    /** 从文档 source（case:{id}）解析案例 ID */
    private Long parseCaseIdFromSource(String source) {
        if (!StringUtils.hasText(source) || !source.startsWith("case:")) {
            return null;
        }
        try {
            return Long.parseLong(source.substring("case:".length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 相关度：优先用 metadata.score，缺省用索引位置估算 */
    private Double scoreOf(Map<String, Object> md) {
        Object score = md.get("score");
        if (score instanceof Number n) {
            double v = n.doubleValue();
            if (v > 0 && v <= 1) {
                return Math.round(v * 100.0) / 100.0;
            }
        }
        return null;
    }

    /** 版本展示：v2.4（缺省省略） */
    private String versionText(Map<String, Object> md) {
        Object v = md.get("version");
        if (v == null) {
            return null;
        }
        return v.toString().startsWith("v") ? v.toString() : "v" + v;
    }

    /** 命中片段：去除换行压缩为单行，最多 200 字 */
    private String snippet(String text, String query) {
        String clean = text.replaceAll("\\s+", " ").trim();
        int idx = StringUtils.hasText(query) ? clean.indexOf(query) : -1;
        if (idx > 80) {
            clean = "…" + clean.substring(Math.max(0, idx - 60));
        }
        return clean.length() <= 200 ? clean : clean.substring(0, 200) + "…";
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long parseLong(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析引用来源 JSON（供 API 展示）
     */
    public List<DiagnosisReferenceResponse> parseReferences(String referencesJson) {
        if (referencesJson == null || referencesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(referencesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, DiagnosisReferenceResponse.class));
        } catch (Exception e) {
            return List.of();
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
            JsonNode root = objectMapper.readTree(snapshotJson);
            JsonNode event = root.path("event");
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
     * @param snapshotJson 故障快照 JSON
     * @param base 原诊断记录
     * @param question 追问问题
     * @return 模型回答
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
        return chatGateway.chat(new ChatRequest("followup-" + UUID.randomUUID(), user.toString(),
                "你是一名资深的 SRE 运维专家，基于给定的故障快照与已有诊断回答用户的追问。直接输出答案，不要输出 JSON。",
                Map.of())).answer();
    }

    /**
     * 校验诊断必填字段后写入记录，避免空对象或越界置信度被标记为成功。
     * @param record 待保存的诊断记录
     * @param result 模型返回的结构化诊断
     * @return 无返回值
     * @throws JsonProcessingException 列表序列化失败
     */
    private void applyResult(DiagnosisRecordEntity record, DiagnosisResult result) throws JsonProcessingException {
        if (result == null || !StringUtils.hasText(result.getRootCause())
                || result.getConfidence() == null || result.getConfidence() < 0 || result.getConfidence() > 100
                || result.getEvidence() == null || result.getEvidence().isEmpty()
                || result.getEvidence().stream().anyMatch(item -> !StringUtils.hasText(item))
                || result.getSuggestions() == null || result.getSuggestions().isEmpty()
                || result.getSuggestions().stream().anyMatch(item -> !StringUtils.hasText(item))) {
            throw new IllegalStateException("模型诊断结果缺少有效根因、证据、建议或置信度（0-100）");
        }
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
