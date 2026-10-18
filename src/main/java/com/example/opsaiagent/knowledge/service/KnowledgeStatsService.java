package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.knowledge.dto.KnowledgeCoverageResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHealthResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHealthResponse.KnowledgeHealthItem;
import com.example.opsaiagent.knowledge.dto.KnowledgeStatsResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeTodoResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeTodoResponse.KnowledgeTodoItem;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeConflictEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeConflictMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeHitLogMapper;
import com.example.opsaiagent.mapper.OpsServiceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库统计聚合服务（P1）
 * 总览统计 / 服务覆盖度 / 待办聚合（待审核、待沉淀、冲突、超期）。
 * 统计数据来自 knowledge_document / knowledge_case / knowledge_conflict / knowledge_hit_log 四张表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeStatsService {

    /** 待办列表单类目最多返回条数 */
    private static final int TODO_LIMIT = 10;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeCaseMapper caseMapper;
    private final KnowledgeHitLogMapper hitLogMapper;
    private final KnowledgeConflictMapper conflictMapper;
    private final OpsServiceMapper serviceMapper;
    private final ObjectMapper objectMapper;

    /**
     * 知识库总览统计
     * @return 各维度计数（文档/案例/冲突/本月命中/健康度概览）
     */
    public KnowledgeStatsResponse stats() {
        LocalDateTime now = LocalDateTime.now();
        // 本月起始：当月 1 日 00:00
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        long totalDocs = documentMapper.selectCount(null);
        long pendingDocs = documentMapper.selectCount(docStatus("PENDING"));
        long expiredDocs = documentMapper.selectCount(docExpired(now));
        long zeroHitDocs = documentMapper.selectCount(docZeroHit());
        long orphanDocs = documentMapper.selectCount(docOrphan());
        long totalCases = caseMapper.selectCount(null);
        long pendingCases = caseMapper.selectCount(caseStatus("PENDING"));
        long confirmedCases = caseMapper.selectCount(caseStatus("CONFIRMED"));
        long pendingConflicts = conflictMapper.selectCount(conflictPending());
        long monthHits = hitLogMapper.selectCount(
                new LambdaQueryWrapper<com.example.opsaiagent.knowledge.entity.KnowledgeHitLogEntity>()
                        .ge(com.example.opsaiagent.knowledge.entity.KnowledgeHitLogEntity::getCreatedAt, monthStart));

        return KnowledgeStatsResponse.builder()
                .totalDocs(totalDocs)
                .pendingDocs(pendingDocs)
                .expiredDocs(expiredDocs)
                .zeroHitDocs(zeroHitDocs)
                .orphanDocs(orphanDocs)
                .totalCases(totalCases)
                .pendingCases(pendingCases)
                .confirmedCases(confirmedCases)
                .pendingConflicts(pendingConflicts)
                .monthHits(monthHits)
                .build();
    }

    /**
     * 服务覆盖度：统计每个已注册服务关联的知识文档数（含已审核数），并计算服务级覆盖率
     * @return 覆盖度汇总 + 服务明细
     */
    public KnowledgeCoverageResponse coverage() {
        List<OpsServiceEntity> services = serviceMapper.selectList(null);
        long coveredServices = 0;
        List<KnowledgeCoverageResponse.ServiceCoverageItem> details = new ArrayList<>();
        for (OpsServiceEntity svc : services) {
            if (svc == null || !StringUtils.hasText(svc.getName())) {
                continue;
            }
            // service_ids 为 JSON 数组字符串，用带引号的精确片段匹配，避免前缀误匹配
            String jsonKey = "\"" + svc.getName() + "\"";
            long docCount = documentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                            .like(KnowledgeDocumentEntity::getServiceIds, jsonKey));
            long verifiedCount = documentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                            .like(KnowledgeDocumentEntity::getServiceIds, jsonKey)
                            .eq(KnowledgeDocumentEntity::getTrustStatus, "VERIFIED"));
            if (docCount > 0) {
                coveredServices++;
            }
            details.add(KnowledgeCoverageResponse.ServiceCoverageItem.builder()
                    .service(svc.getName())
                    .docCount(docCount)
                    .verifiedCount(verifiedCount)
                    .build());
        }
        double coverageRate = services.isEmpty() ? 0.0
                : Math.round(coveredServices / (double) services.size() * 100.0) / 100.0;
        return KnowledgeCoverageResponse.builder()
                .totalServices((long) services.size())
                .coveredServices(coveredServices)
                .coverageRate(coverageRate)
                .details(details)
                .build();
    }

    /**
     * 待办聚合：待审核文档 / 待沉淀案例 / 冲突待裁定 / 超期文档（各取最近 TODO_LIMIT 条）
     * @return 聚合待办列表
     */
    public KnowledgeTodoResponse todos() {
        LocalDateTime now = LocalDateTime.now();

        List<KnowledgeTodoItem> pendingDocs = documentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                                .eq(KnowledgeDocumentEntity::getTrustStatus, "PENDING")
                                .orderByDesc(KnowledgeDocumentEntity::getCreatedAt)
                                .last("LIMIT " + TODO_LIMIT))
                .stream().map(d -> toDocumentItem(d, "PENDING")).toList();

        List<KnowledgeTodoItem> pendingCases = caseMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeCaseEntity>()
                                .eq(KnowledgeCaseEntity::getStatus, "PENDING")
                                .orderByDesc(KnowledgeCaseEntity::getCreatedAt)
                                .last("LIMIT " + TODO_LIMIT))
                .stream().map(this::toCaseItem).toList();

        List<KnowledgeTodoItem> conflicts = conflictMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeConflictEntity>()
                                .eq(KnowledgeConflictEntity::getStatus, "PENDING")
                                .orderByDesc(KnowledgeConflictEntity::getCreatedAt)
                                .last("LIMIT " + TODO_LIMIT))
                .stream().map(this::toConflictItem).toList();

        List<KnowledgeTodoItem> expiredDocs = documentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                                .isNotNull(KnowledgeDocumentEntity::getReviewAt)
                                .lt(KnowledgeDocumentEntity::getReviewAt, now)
                                .orderByAsc(KnowledgeDocumentEntity::getReviewAt)
                                .last("LIMIT " + TODO_LIMIT))
                .stream().map(d -> toDocumentItem(d, "EXPIRED")).toList();

        return KnowledgeTodoResponse.builder()
                .pendingDocs(pendingDocs)
                .pendingCases(pendingCases)
                .conflicts(conflicts)
                .expiredDocs(expiredDocs)
                .build();
    }

    // ==================== 查询条件构造 ====================

    /**
     * 健康度明细（P2）：超期 / 零命中 / 孤儿三类文档明细列表
     * @return 三类明细（各维度独立查询，返回全部命中记录）
     */
    public KnowledgeHealthResponse health() {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeHealthResponse response = new KnowledgeHealthResponse();
        response.setExpired(documentMapper.selectList(docExpired(now)).stream()
                .map(this::toHealthItem)
                .toList());
        response.setZeroHit(documentMapper.selectList(docZeroHit()).stream()
                .map(this::toHealthItem)
                .toList());
        response.setOrphan(documentMapper.selectList(docOrphan()).stream()
                .map(this::toHealthItem)
                .toList());
        return response;
    }

    /**
     * 文档实体转健康度明细条目
     * @param doc 文档实体
     * @return 明细条目
     */
    private KnowledgeHealthItem toHealthItem(KnowledgeDocumentEntity doc) {
        KnowledgeHealthItem item = new KnowledgeHealthItem();
        item.setId(doc.getId());
        item.setTitle(doc.getTitle());
        item.setTrustStatus(doc.getTrustStatus());
        item.setHitCount(doc.getHitCount());
        item.setReviewAt(doc.getReviewAt());
        return item;
    }

    private LambdaQueryWrapper<KnowledgeDocumentEntity> docStatus(String status) {
        return new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTrustStatus, status);
    }

    private LambdaQueryWrapper<KnowledgeDocumentEntity> docExpired(LocalDateTime now) {
        return new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .isNotNull(KnowledgeDocumentEntity::getReviewAt)
                .lt(KnowledgeDocumentEntity::getReviewAt, now);
    }

    private LambdaQueryWrapper<KnowledgeDocumentEntity> docZeroHit() {
        return new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .and(w -> w.eq(KnowledgeDocumentEntity::getHitCount, 0L)
                        .or().isNull(KnowledgeDocumentEntity::getHitCount));
    }

    private LambdaQueryWrapper<KnowledgeDocumentEntity> docOrphan() {
        return new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .and(w -> w.eq(KnowledgeDocumentEntity::getChunkCount, 0)
                        .or().isNull(KnowledgeDocumentEntity::getChunkCount));
    }

    private LambdaQueryWrapper<KnowledgeCaseEntity> caseStatus(String status) {
        return new LambdaQueryWrapper<KnowledgeCaseEntity>()
                .eq(KnowledgeCaseEntity::getStatus, status);
    }

    private LambdaQueryWrapper<KnowledgeConflictEntity> conflictPending() {
        return new LambdaQueryWrapper<KnowledgeConflictEntity>()
                .eq(KnowledgeConflictEntity::getStatus, "PENDING");
    }

    // ==================== 待办条目转换 ====================

    private KnowledgeTodoItem toDocumentItem(KnowledgeDocumentEntity doc, String status) {
        return KnowledgeTodoItem.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .service(firstServiceId(doc.getServiceIds()))
                .itemType("DOCUMENT")
                .status(status)
                .createdAt(doc.getCreatedAt())
                .dueAt(doc.getReviewAt())
                .build();
    }

    private KnowledgeTodoItem toCaseItem(KnowledgeCaseEntity entity) {
        return KnowledgeTodoItem.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .service(entity.getService())
                .itemType("CASE")
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private KnowledgeTodoItem toConflictItem(KnowledgeConflictEntity entity) {
        String title = "文档#" + entity.getDocId() + " 与 #"
                + (entity.getMatchedDocId() == null ? "-" : entity.getMatchedDocId()) + " 冲突";
        return KnowledgeTodoItem.builder()
                .id(entity.getId())
                .title(title)
                .service(null)
                .itemType("CONFLICT")
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 解析 service_ids JSON 数组，取第一个服务名（无则返回 null）
     */
    private String firstServiceId(String serviceIdsJson) {
        if (!StringUtils.hasText(serviceIdsJson)) {
            return null;
        }
        try {
            List<String> ids = objectMapper.readValue(serviceIdsJson, new TypeReference<List<String>>() {});
            return ids == null || ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.debug("service_ids 解析失败: {}", serviceIdsJson);
            return null;
        }
    }
}
