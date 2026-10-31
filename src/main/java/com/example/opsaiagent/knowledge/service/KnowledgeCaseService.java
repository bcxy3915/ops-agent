package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.mapper.DiagnosisRecordMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.CaseFlowStep;
import com.example.opsaiagent.knowledge.dto.CaseReviseRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseDetailResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseSummaryResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategyFactory;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.mapper.FailureSnapshotMapper;
import com.example.opsaiagent.security.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故障案例服务（T19/T20/T21）
 * 列表（待确认/已沉淀）、详情（五步闭环链路）、确认沉淀 / 修正沉淀 / 丢弃。
 * 确认沉淀时：生成 CASE 层知识文档 → 分块 → 写入 knowledge_chunk → 向量化入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCaseService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_DISCARDED = "DISCARDED";
    public static final String MASK_NONE = "NONE";
    public static final String MASK_PARTIAL = "PARTIAL";
    public static final String MASK_FULL = "FULL";
    /** CASE 文档层级 */
    public static final String LAYER_CASE = "CASE";
    /** CASE 文档信任状态 */
    public static final String TRUST_VERIFIED = "VERIFIED";
    /** CASE 案例可见性：企业 */
    public static final String VISIBILITY_TENANT = "TENANT";
    /** CASE 文档向量 source 前缀 */
    public static final String CASE_VECTOR_SOURCE_PREFIX = "case:";

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);

    /** knowledge_chunk 批量插入的每批条数 */
    private static final int CHUNK_INSERT_BATCH_SIZE = 500;

    private final KnowledgeCaseMapper caseMapper;
    private final DiagnosisRecordMapper diagnosisRecordMapper;
    private final AlertEventMapper alertEventMapper;
    private final FailureSnapshotMapper failureSnapshotMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final VectorIndexer vectorIndexer;
    private final DocumentParserStrategyFactory parserStrategyFactory;
    private final ObjectMapper objectMapper;
    private final KnowledgeQueryScope queryScope;

    /**
     * 分页查询案例列表
     * @param status 状态筛选（PENDING/CONFIRMED/DISCARDED，可选）
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 分页结果
     */
    public PageResult<KnowledgeCaseSummaryResponse> list(String status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<KnowledgeCaseEntity> wrapper = queryScope.current().cases();
        if (StringUtils.hasText(status)) {
            wrapper.eq(KnowledgeCaseEntity::getStatus, status);
        }
        wrapper.orderByDesc(KnowledgeCaseEntity::getCreatedAt);

        Page<KnowledgeCaseEntity> result = caseMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<KnowledgeCaseSummaryResponse> records = result.getRecords().stream()
                .map(this::toSummary)
                .toList();
        return PageResult.<KnowledgeCaseSummaryResponse>builder()
                .records(records)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .pages(result.getPages())
                .build();
    }

    /**
     * 案例详情：五步闭环链路 + 置信度 + 证据 + 脱敏
     * 链路：告警(ALERT) → 快照(SNAPSHOT) → AI 诊断(DIAGNOSIS) → 有效处置(ACTION) → 闭环恢复(RECOVERY)
     * @param id 案例 ID
     * @return 案例详情
     */
    public KnowledgeCaseDetailResponse getById(Long id) {
        KnowledgeCaseEntity entity = requireCase(id);
        DiagnosisRecordEntity diag = entity.getDiagnosisRecordId() == null ? null
                : diagnosisRecordMapper.selectById(entity.getDiagnosisRecordId());
        AlertEventEntity alert = entity.getSourceAlertId() == null ? null
                : alertEventMapper.selectById(entity.getSourceAlertId());
        FailureSnapshotEntity snapshot = diag != null && diag.getSnapshotId() != null
                ? failureSnapshotMapper.selectById(diag.getSnapshotId())
                : null;
        return toDetail(entity, diag, alert, snapshot);
    }

    /**
     * 确认沉淀：PENDING → CONFIRMED，生成 CASE 知识文档并分块向量化
     * @param id 案例 ID
     * @return 确认后的案例详情
     */
    @Transactional
    public KnowledgeCaseDetailResponse confirm(Long id) {
        KnowledgeCaseEntity entity = requireCase(id);
        requirePending(entity);
        String reviewer = currentReviewer();
        entity.setStatus(STATUS_CONFIRMED);
        // 历史案例可能缺少归属，确认时按当前上下文补齐，保证沉淀为 TENANT 知识
        if (!StringUtils.hasText(entity.getTenantId())) {
            entity.setTenantId(currentTenantId());
        }
        if (entity.getDeptId() == null) {
            entity.setDeptId(SecurityUtils.getCurrentDeptId());
        }
        entity.setVisibility(VISIBILITY_TENANT);
        if (!StringUtils.hasText(entity.getRealRootCause())) {
            entity.setRealRootCause(entity.getRootCause());
        }
        if (!StringUtils.hasText(entity.getMaskedStatus())) {
            entity.setMaskedStatus(MASK_NONE);
        }
        entity.setClosedLoopAt(LocalDateTime.now());
        entity.setConfirmedBy(reviewer);
        caseMapper.updateById(entity);
        // 沉淀为 CASE 知识文档：分块 + 向量化，可被检索引用
        indexCaseDocument(entity);
        log.info("故障案例确认沉淀: id={}, title={}, reviewer={}", id, entity.getTitle(), reviewer);
        return getById(id);
    }

    /**
     * 修正沉淀：先应用人工修正内容，再执行确认沉淀
     * @param id 案例 ID
     * @param request 修正请求（真实根因/有效处置/脱敏状态，均可选）
     * @return 修正并确认后的案例详情
     */
    @Transactional
    public KnowledgeCaseDetailResponse revise(Long id, CaseReviseRequest request) {
        KnowledgeCaseEntity entity = requireCase(id);
        requirePending(entity);
        if (request.getRealRootCause() != null) {
            entity.setRealRootCause(request.getRealRootCause());
        }
        if (request.getEffectiveAction() != null) {
            entity.setEffectiveAction(request.getEffectiveAction());
        }
        if (StringUtils.hasText(request.getMaskedStatus())) {
            if (!isValidMaskedStatus(request.getMaskedStatus())) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_CASE_INVALID,
                        "无效的脱敏状态: " + request.getMaskedStatus() + "（仅支持 NONE/PARTIAL/FULL）");
            }
            entity.setMaskedStatus(request.getMaskedStatus());
        }
        caseMapper.updateById(entity);
        return confirm(id);
    }

    /**
     * 丢弃候选：PENDING → DISCARDED
     * @param id 案例 ID
     */
    @Transactional
    public void discard(Long id) {
        KnowledgeCaseEntity entity = requireCase(id);
        requirePending(entity);
        entity.setStatus(STATUS_DISCARDED);
        caseMapper.updateById(entity);
        log.info("故障案例已丢弃: id={}, title={}", id, entity.getTitle());
    }

    /**
     * 案例自动生成（P1）
     * 从成功的主诊断记录（status=SUCCESS 且非追问）批量生成待确认候选案例，按诊断记录去重。
     * @param limit 本次最多生成条数（1~100）
     * @return 本次新生成的候选数量
     */
    @Transactional
    public int generateCandidates(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<DiagnosisRecordEntity> records = diagnosisRecordMapper.selectList(
                new LambdaQueryWrapper<DiagnosisRecordEntity>()
                        .eq(DiagnosisRecordEntity::getStatus, "SUCCESS")
                        .isNull(DiagnosisRecordEntity::getQuestion)
                        .orderByDesc(DiagnosisRecordEntity::getCreatedAt)
                        .last("LIMIT " + safeLimit));

        int generated = 0;
        for (DiagnosisRecordEntity diag : records) {
            Long existed = caseMapper.selectCount(new LambdaQueryWrapper<KnowledgeCaseEntity>()
                    .eq(KnowledgeCaseEntity::getDiagnosisRecordId, diag.getId()));
            if (existed != null && existed > 0) {
                continue;
            }
            KnowledgeCaseEntity entity = new KnowledgeCaseEntity();
            entity.setSourceAlertId(diag.getAlertEventId());
            entity.setDiagnosisRecordId(diag.getId());
            entity.setService(diag.getService());
            entity.setTitle(buildCaseTitle(diag));
            entity.setSymptom(buildCaseSymptom(diag));
            entity.setRootCause(diag.getRootCause());
            entity.setEvidence(diag.getEvidence());
            entity.setConfidence(diag.getConfidence());
            entity.setMaskedStatus(MASK_NONE);
            entity.setStatus(STATUS_PENDING);
            entity.setCitedCount(0);
            entity.setTenantId(currentTenantId());
            entity.setDeptId(SecurityUtils.getCurrentDeptId());
            entity.setVisibility(VISIBILITY_TENANT);
            caseMapper.insert(entity);
            generated++;
        }
        if (generated > 0) {
            log.info("案例自动生成完成: 新候选 {} 条（本次扫描 {} 条诊断）", generated, records.size());
        }
        return generated;
    }

    /**
     * 由诊断记录拼接案例标题
     * @param diag 诊断记录
     * @return 案例标题（截断 100 字）
     */
    private String buildCaseTitle(DiagnosisRecordEntity diag) {
        String base = StringUtils.hasText(diag.getRootCause()) ? diag.getRootCause()
                : (StringUtils.hasText(diag.getSummary()) ? diag.getSummary() : "诊断记录");
        String prefix = StringUtils.hasText(diag.getService()) ? "[" + diag.getService() + "] " : "";
        return truncate(prefix + "故障根因：" + base, 100);
    }

    /**
     * 由诊断记录拼接故障现象
     * @param diag 诊断记录
     * @return 症状描述
     */
    private String buildCaseSymptom(DiagnosisRecordEntity diag) {
        if (StringUtils.hasText(diag.getSummary())) {
            return diag.getSummary();
        }
        if (StringUtils.hasText(diag.getRuleName())) {
            return diag.getRuleName() + (diag.getMetric() == null ? "" : "：" + diag.getMetric());
        }
        return "AI 诊断异常";
    }

    /**
     * 沉淀 CASE 知识文档：生成文档实体 → 分块 → 写 chunk → 向量化
     */
    private void indexCaseDocument(KnowledgeCaseEntity entity) {
        String content = buildCaseContent(entity);
        if (!StringUtils.hasText(content)) {
            log.warn("案例内容为空，跳过知识沉淀: id={}", entity.getId());
            return;
        }
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setTitle(entity.getTitle());
        doc.setDocType("MARKDOWN");
        doc.setLayer(LAYER_CASE);
        doc.setTrustStatus(TRUST_VERIFIED);
        doc.setTenantId(entity.getTenantId());
        doc.setDeptId(entity.getDeptId());
        doc.setVisibility(VISIBILITY_TENANT);
        doc.setReviewStatus("APPROVED");
        doc.setReviewLevel("TENANT");
        doc.setReviewedBy(entity.getConfirmedBy());
        doc.setReviewedAt(LocalDateTime.now());
        doc.setLastReviewAt(LocalDateTime.now());
        doc.setVersion(1);
        doc.setOwner(entity.getConfirmedBy());
        doc.setServiceIds(toJson(List.of(entity.getService())));
        doc.setTags(toJson(List.of("case")));
        doc.setHitCount(0L);
        doc.setSource(CASE_VECTOR_SOURCE_PREFIX + entity.getId());
        doc.setFilePath(CASE_VECTOR_SOURCE_PREFIX + entity.getId());
        doc.setRawContent(content);
        doc.setChunkCount(0);
        documentMapper.insert(doc);

        List<Document> chunks;
        // 案例内容为 Markdown，按标题层级结构化切分，与文档审核路径行为一致
        List<Document> structured = parserStrategyFactory.getStrategy("MARKDOWN")
                .parse(new ByteArrayResource(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        chunks = structured.isEmpty() ? List.of() : vectorIndexer.split(structured);
        if (chunks.isEmpty()) {
            // 短文本可能被切分器全部丢弃，退化为整段切分（内部有单块兜底）
            chunks = vectorIndexer.split(content);
        }

        List<KnowledgeChunkEntity> chunkEntities = new ArrayList<>();
        int ordinal = 1;
        for (Document chunk : chunks) {
            KnowledgeChunkEntity chunkEntity = new KnowledgeChunkEntity();
            chunkEntity.setDocId(doc.getId());
            chunkEntity.setTenantId(doc.getTenantId());
            chunkEntity.setDeptId(doc.getDeptId());
            chunkEntity.setOrdinal(ordinal);
            chunkEntity.setContent(chunk.getText());
            chunkEntity.setSection(extractSection(chunk.getText(), "MARKDOWN"));
            chunkEntities.add(chunkEntity);
            ordinal++;
        }
        // 批量写入分块（每 500 条一批），主键回填后供向量 metadata 引用
        for (int i = 0; i < chunkEntities.size(); i += CHUNK_INSERT_BATCH_SIZE) {
            chunkMapper.insertBatch(
                    chunkEntities.subList(i, Math.min(i + CHUNK_INSERT_BATCH_SIZE, chunkEntities.size())));
        }

        List<Document> vectorDocs = new ArrayList<>();
        String tenantId = doc.getTenantId() == null ? "" : doc.getTenantId();
        String deptId = doc.getDeptId() == null ? "" : String.valueOf(doc.getDeptId());
        String owner = doc.getOwner() == null ? "" : doc.getOwner();
        ordinal = 1;
        for (Document chunk : chunks) {
            KnowledgeChunkEntity chunkEntity = chunkEntities.get(ordinal - 1);
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("source", CASE_VECTOR_SOURCE_PREFIX + doc.getId());
            metadata.put("docId", String.valueOf(doc.getId()));
            metadata.put("title", doc.getTitle() == null ? "" : doc.getTitle());
            metadata.put("layer", doc.getLayer());
            metadata.put("trustStatus", doc.getTrustStatus());
            metadata.put("tenantId", tenantId);
            metadata.put("deptId", deptId);
            metadata.put("visibility", VISIBILITY_TENANT);
            metadata.put("reviewStatus", "APPROVED");
            metadata.put("owner", owner);
            metadata.put("ordinal", String.valueOf(ordinal));
            metadata.put("chunkId", String.valueOf(chunkEntity.getId()));
            Document vectorDoc = new Document(chunk.getText(), metadata);
            vectorDocs.add(vectorDoc);
            ordinal++;
        }
        vectorIndexer.write(vectorDocs);
        doc.setChunkCount(chunkEntities.size());
        documentMapper.updateById(doc);
        log.info("案例知识沉淀完成: caseId={}, docId={}, chunks={}", entity.getId(), doc.getId(), chunkEntities.size());
    }

    /**
     * 拼接案例知识文档内容（markdown 结构）
     */
    private String buildCaseContent(KnowledgeCaseEntity entity) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(entity.getTitle())) {
            sb.append("# ").append(entity.getTitle()).append('\n');
        }
        if (StringUtils.hasText(entity.getSymptom())) {
            sb.append("## 故障现象\n").append(entity.getSymptom()).append('\n');
        }
        String root = StringUtils.hasText(entity.getRealRootCause()) ? entity.getRealRootCause() : entity.getRootCause();
        if (StringUtils.hasText(root)) {
            sb.append("## 根因\n").append(root).append('\n');
        }
        if (StringUtils.hasText(entity.getEffectiveAction())) {
            sb.append("## 处置动作\n").append(entity.getEffectiveAction()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 实体转列表项
     */
    private KnowledgeCaseSummaryResponse toSummary(KnowledgeCaseEntity entity) {
        return KnowledgeCaseSummaryResponse.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .deptId(entity.getDeptId())
                .visibility(entity.getVisibility())
                .sourceAlertId(entity.getSourceAlertId())
                .diagnosisRecordId(entity.getDiagnosisRecordId())
                .service(entity.getService())
                .title(entity.getTitle())
                .confidence(entity.getConfidence())
                .status(entity.getStatus())
                .maskedStatus(entity.getMaskedStatus())
                .durationMin(entity.getDurationMin())
                .confirmedBy(entity.getConfirmedBy())
                .closedLoopAt(entity.getClosedLoopAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 实体 + 链路关联数据 → 详情
     */
    private KnowledgeCaseDetailResponse toDetail(KnowledgeCaseEntity entity,
                                                 DiagnosisRecordEntity diag,
                                                 AlertEventEntity alert,
                                                 FailureSnapshotEntity snapshot) {
        return KnowledgeCaseDetailResponse.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .deptId(entity.getDeptId())
                .visibility(entity.getVisibility())
                .sourceAlertId(entity.getSourceAlertId())
                .diagnosisRecordId(entity.getDiagnosisRecordId())
                .service(entity.getService())
                .title(entity.getTitle())
                .symptom(entity.getSymptom())
                .rootCause(entity.getRootCause())
                .realRootCause(entity.getRealRootCause())
                .evidence(parseEvidence(entity.getEvidence()))
                .effectiveAction(entity.getEffectiveAction())
                .confidence(entity.getConfidence())
                .maskedStatus(entity.getMaskedStatus())
                .status(entity.getStatus())
                .durationMin(entity.getDurationMin())
                .confirmedBy(entity.getConfirmedBy())
                .citedCount(entity.getCitedCount())
                .closedLoopAt(entity.getClosedLoopAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .flow(buildFlow(entity, diag, alert, snapshot))
                .build();
    }

    /**
     * 拼装五步闭环链路
     */
    private List<CaseFlowStep> buildFlow(KnowledgeCaseEntity entity,
                                         DiagnosisRecordEntity diag,
                                         AlertEventEntity alert,
                                         FailureSnapshotEntity snapshot) {
        List<CaseFlowStep> flow = new ArrayList<>();
        // 1. 告警触发
        if (alert != null) {
            flow.add(CaseFlowStep.builder()
                    .step("ALERT")
                    .title("告警触发")
                    .content(buildAlertContent(alert))
                    .time(alert.getFiredAt())
                    .build());
        }
        // 2. 快照采集
        if (snapshot != null) {
            flow.add(CaseFlowStep.builder()
                    .step("SNAPSHOT")
                    .title("快照采集")
                    .content(buildSnapshotContent(snapshot))
                    .time(snapshot.getCreatedAt())
                    .build());
        }
        // 3. AI 诊断
        if (diag != null) {
            flow.add(CaseFlowStep.builder()
                    .step("DIAGNOSIS")
                    .title("AI 诊断")
                    .content(diag.getRootCause() == null ? "无诊断根因" : diag.getRootCause()
                            + "（置信度 " + diag.getConfidence() + "%）")
                    .time(diag.getCreatedAt())
                    .build());
        }
        // 4. 有效处置
        if (StringUtils.hasText(entity.getEffectiveAction())) {
            flow.add(CaseFlowStep.builder()
                    .step("ACTION")
                    .title("有效处置")
                    .content(entity.getEffectiveAction())
                    .time(entity.getClosedLoopAt())
                    .build());
        }
        // 5. 闭环恢复
        if (alert != null) {
            flow.add(CaseFlowStep.builder()
                    .step("RECOVERY")
                    .title("闭环恢复")
                    .content(buildRecoveryContent(alert))
                    .time(alert.getResolvedAt())
                    .build());
        }
        return flow;
    }

    private String buildAlertContent(AlertEventEntity alert) {
        return String.format("%s：%s 超阈值，当前值 %s / 阈值 %s",
                alert.getRuleName() == null ? "未知规则" : alert.getRuleName(),
                alert.getMetric() == null ? "指标" : alert.getMetric(),
                alert.getCurrentValue() == null ? "-" : alert.getCurrentValue(),
                alert.getThreshold() == null ? "-" : alert.getThreshold());
    }

    private String buildSnapshotContent(FailureSnapshotEntity snapshot) {
        // 快照 JSON 可能较长，取结构化关键信息；解析失败则截断原文
        String json = snapshot.getSnapshotJson();
        if (!StringUtils.hasText(json)) {
            return "无快照数据";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String serviceStatus = node.path("serviceStatus").path("status").asText(null);
            JsonNode metricWindow = node.path("metricWindow");
            StringBuilder sb = new StringBuilder();
            if (serviceStatus != null && !serviceStatus.isBlank()) {
                sb.append("服务状态=").append(serviceStatus).append("；");
            }
            if (metricWindow.isArray()) {
                sb.append("指标采样 ").append(metricWindow.size()).append(" 条");
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("快照 JSON 解析失败: {}", e.getMessage());
        }
        return truncate(json, 100);
    }

    private String buildRecoveryContent(AlertEventEntity alert) {
        if (alert.getResolvedAt() != null) {
            return "告警已恢复，耗时 " + alert.getResolvedAt() + "（状态 " + alert.getStatus() + "）";
        }
        return "告警未恢复（状态 " + alert.getStatus() + "）";
    }

    /**
     * 解析证据 JSON 数组
     */
    private List<String> parseEvidence(String evidenceJson) {
        if (!StringUtils.hasText(evidenceJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(evidenceJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("证据反序列化失败: {}", evidenceJson, e);
            return List.of();
        }
    }

    /**
     * 列表转 JSON 数组字符串
     */
    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("列表序列化失败", e);
            return null;
        }
    }

    /**
     * 从块文本提取章节标题（markdown 取首个标题行；其他类型取首个非空行截断）
     */
    String extractSection(String text, String docType) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if ("MARKDOWN".equals(docType)) {
            Matcher m = MD_HEADING.matcher(text);
            if (m.find()) {
                String heading = m.group(1).trim();
                return heading.length() > 100 ? heading.substring(0, 100) : heading;
            }
        }
        String firstLine = text.lines().filter(StringUtils::hasText).findFirst().orElse("");
        return firstLine.length() > 100 ? firstLine.substring(0, 100) : firstLine;
    }

    /**
     * 截断字符串
     */
    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private KnowledgeCaseEntity requireCase(Long id) {
        KnowledgeCaseEntity entity = caseMapper.selectById(id);
        if (entity == null || !queryScope.current().canAccess(entity)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CASE_NOT_FOUND, "故障案例不存在: " + id);
        }
        return entity;
    }

    private String currentTenantId() {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return StringUtils.hasText(tenantId) ? tenantId : "t-default";
    }

    private void requirePending(KnowledgeCaseEntity entity) {
        if (!STATUS_PENDING.equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CASE_INVALID,
                    "仅待确认案例可操作，当前状态: " + entity.getStatus());
        }
    }

    private String currentReviewer() {
        String reviewer = SecurityUtils.getCurrentUsername();
        return StringUtils.hasText(reviewer) ? reviewer : "system";
    }

    private boolean isValidMaskedStatus(String status) {
        return MASK_NONE.equals(status) || MASK_PARTIAL.equals(status) || MASK_FULL.equals(status);
    }
}
