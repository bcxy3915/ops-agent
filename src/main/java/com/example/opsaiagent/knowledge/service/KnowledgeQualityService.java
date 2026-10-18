package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.ConflictResolveRequest;
import com.example.opsaiagent.knowledge.dto.ConflictResolveResponse;
import com.example.opsaiagent.knowledge.dto.QualityCheckRequest;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse.ConflictCandidate;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse.MaskedPreview;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse.SensitiveItem;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse.StaleDocItem;
import com.example.opsaiagent.knowledge.entity.KnowledgeConflictEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeConflictMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.retrieval.HybridRetriever;
import com.example.opsaiagent.sensitive.util.OpsSensitiveScanner;
import com.example.opsaiagent.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识文档上传质检服务（P1）
 * check：冲突检测（标题/内容相似） + 过时检测（复核到期） + 敏感信息脱敏预览；
 * resolve：冲突三态裁定（KEEP_NEW 以新为准 / KEEP_OLD 保留原值 / MANUAL 人工裁定）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeQualityService {

    /** 冲突类型：标题相似 */
    public static final String CONFLICT_TITLE = "TITLE";
    /** 冲突类型：内容相似 */
    public static final String CONFLICT_CONTENT = "CONTENT";
    /** 裁定：以新文档为准 */
    public static final String DECISION_KEEP_NEW = "KEEP_NEW";
    /** 裁定：保留原文档 */
    public static final String DECISION_KEEP_OLD = "KEEP_OLD";
    /** 裁定：标记人工处理 */
    public static final String DECISION_MANUAL = "MANUAL";

    /** 内容冲突相似度阈值（0~1，达到即判定为候选冲突） */
    private static final double CONTENT_CONFLICT_THRESHOLD = 0.6;
    /** 内容冲突检索条数 */
    private static final int CONFLICT_TOP_K = 5;
    /** 内容检索使用的正文截断长度 */
    private static final int RETRIEVE_CONTENT_LIMIT = 200;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeConflictMapper conflictMapper;
    private final HybridRetriever hybridRetriever;

    /**
     * 上传质检：冲突检测 + 过时检测 + 敏感信息脱敏预览
     * @param request 质检请求（title/content 必填）
     * @return 质检结果
     */
    public QualityCheckResponse check(QualityCheckRequest request) {
        if (request == null || !StringUtils.hasText(request.getTitle()) || !StringUtils.hasText(request.getContent())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "标题和内容不能为空");
        }
        String title = request.getTitle().trim();
        String content = request.getContent();

        List<ConflictCandidate> conflicts = new ArrayList<>();
        Set<Long> seenDocIds = new LinkedHashSet<>();

        // 1. 标题冲突：标题相同/包含的已有文档
        List<KnowledgeDocumentEntity> sameTitleDocs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getTitle, title)
                        .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt));
        for (KnowledgeDocumentEntity doc : sameTitleDocs) {
            seenDocIds.add(doc.getId());
            conflicts.add(ConflictCandidate.builder()
                    .matchedDocId(doc.getId())
                    .matchedTitle(doc.getTitle())
                    .conflictType(CONFLICT_TITLE)
                    .similarity(1.0)
                    .build());
        }

        // 2. 内容冲突：向量检索与待上传内容最相似的已有文档（阈值以上）
        String retrieveQuery = title + "\n" + truncate(content, RETRIEVE_CONTENT_LIMIT);
        List<Document> docs = hybridRetriever.retrieve(retrieveQuery, CONFLICT_TOP_K);
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Long docId = parseDocId(doc);
            if (docId == null || seenDocIds.contains(docId)) {
                continue;
            }
            double similarity = Math.round((CONFLICT_TOP_K - i) / (double) CONFLICT_TOP_K * 100.0) / 100.0;
            if (similarity < CONTENT_CONFLICT_THRESHOLD) {
                continue;
            }
            KnowledgeDocumentEntity matched = documentMapper.selectById(docId);
            seenDocIds.add(docId);
            conflicts.add(ConflictCandidate.builder()
                    .matchedDocId(docId)
                    .matchedTitle(matched != null && StringUtils.hasText(matched.getTitle())
                            ? matched.getTitle() : str(doc.getMetadata().get("title")))
                    .conflictType(CONFLICT_CONTENT)
                    .similarity(similarity)
                    .build());
        }

        // 3. 过时检测：同主题（标题相同/内容命中）文档复核已到期
        LocalDateTime now = LocalDateTime.now();
        List<StaleDocItem> staleDocs = new ArrayList<>();
        for (KnowledgeDocumentEntity doc : sameTitleDocs) {
            if (doc.getReviewAt() != null && doc.getReviewAt().isBefore(now)) {
                staleDocs.add(toStaleItem(doc));
            }
        }
        for (Document doc : docs) {
            Long docId = parseDocId(doc);
            if (docId == null) {
                continue;
            }
            KnowledgeDocumentEntity matched = documentMapper.selectById(docId);
            if (matched != null && matched.getReviewAt() != null && matched.getReviewAt().isBefore(now)) {
                if (staleDocs.stream().noneMatch(s -> s.getId().equals(docId))) {
                    staleDocs.add(toStaleItem(matched));
                }
            }
        }

        // 4. 敏感信息脱敏预览
        MaskedPreview maskedPreview = MaskedPreview.builder()
                .maskedContent(OpsSensitiveScanner.mask(content))
                .sensitiveItems(toSensitiveItems(OpsSensitiveScanner.scan(content)))
                .build();

        return QualityCheckResponse.builder()
                .conflicts(conflicts)
                .staleDocs(staleDocs)
                .maskedPreview(maskedPreview)
                .build();
    }

    /**
     * 冲突三态裁定
     * @param conflictId 冲突记录 ID
     * @param request 裁定请求（decision 必填，KEEP_NEW / KEEP_OLD / MANUAL）
     * @return 裁定结果
     */
    @Transactional
    public ConflictResolveResponse resolve(Long conflictId, ConflictResolveRequest request) {
        if (request == null || !isValidDecision(request.getDecision())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CONFLICT_INVALID,
                    "无效的裁定结果（仅支持 KEEP_NEW / KEEP_OLD / MANUAL）");
        }
        KnowledgeConflictEntity conflict = conflictMapper.selectById(conflictId);
        if (conflict == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CONFLICT_NOT_FOUND, "冲突记录不存在: " + conflictId);
        }
        if (!"PENDING".equals(conflict.getStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CONFLICT_INVALID,
                    "仅待裁定冲突可处理，当前状态: " + conflict.getStatus());
        }

        String decision = request.getDecision();
        String operator = SecurityUtils.getCurrentUsername();
        conflict.setStatus("RESOLVED");
        conflict.setDecision(decision);
        conflict.setResolvedBy(operator);
        conflict.setResolvedAt(LocalDateTime.now());
        conflictMapper.updateById(conflict);

        // 裁定联动：KEEP_NEW → 旧文档作废；KEEP_OLD → 新文档作废；MANUAL → 仅标记
        if (DECISION_KEEP_NEW.equals(decision) && conflict.getMatchedDocId() != null) {
            expireDocument(conflict.getMatchedDocId());
        } else if (DECISION_KEEP_OLD.equals(decision) && conflict.getDocId() != null) {
            expireDocument(conflict.getDocId());
        }
        // 新文档记录冲突状态
        KnowledgeDocumentEntity doc = conflict.getDocId() == null ? null : documentMapper.selectById(conflict.getDocId());
        if (doc != null) {
            doc.setConflictStatus(decision);
            documentMapper.updateById(doc);
        }

        log.info("知识冲突裁定完成: conflictId={}, decision={}, operator={}", conflictId, decision, operator);
        return ConflictResolveResponse.builder()
                .conflictId(conflictId)
                .status("RESOLVED")
                .decision(decision)
                .resolvedBy(operator)
                .resolvedAt(conflict.getResolvedAt())
                .build();
    }

    // ==================== 辅助 ====================

    private void expireDocument(Long docId) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc != null) {
            doc.setTrustStatus("EXPIRED");
            documentMapper.updateById(doc);
        }
    }

    private StaleDocItem toStaleItem(KnowledgeDocumentEntity doc) {
        return StaleDocItem.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .reviewAt(doc.getReviewAt())
                .build();
    }

    private List<SensitiveItem> toSensitiveItems(List<OpsSensitiveScanner.SensitiveItem> scanned) {
        List<SensitiveItem> items = new ArrayList<>();
        for (OpsSensitiveScanner.SensitiveItem s : scanned) {
            items.add(SensitiveItem.builder()
                    .type(s.getType())
                    .raw(s.getRaw())
                    .masked(s.getMasked())
                    .count(s.getCount())
                    .build());
        }
        return items;
    }

    private Long parseDocId(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Object v = doc.getMetadata().get("docId");
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isValidDecision(String decision) {
        return DECISION_KEEP_NEW.equals(decision)
                || DECISION_KEEP_OLD.equals(decision)
                || DECISION_MANUAL.equals(decision);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
