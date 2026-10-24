package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseReferenceResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHitLogResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeHitLogEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeCaseMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeHitLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识命中流水服务（P1）
 * 记录知识文档被引用的流水（对话引用 / 检索测试 / 其他检索），
 * 并同步累加 knowledge_document.hit_count 与 knowledge_case.cited_count，
 * 为"本月命中 / 零命中分析 / 案例复用统计"提供数据基础。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeHitLogService {

    /** 命中来源：对话引用 */
    public static final String SOURCE_ASK = "ASK";
    /** 命中来源：检索测试 */
    public static final String SOURCE_TEST = "TEST";
    /** 命中来源：其他检索 */
    public static final String SOURCE_RETRIEVE = "RETRIEVE";

    /** CASE 文档向量 source 前缀（与 KnowledgeCaseService 保持一致） */
    private static final String CASE_SOURCE_PREFIX = "case:";

    private final KnowledgeHitLogMapper hitLogMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeCaseMapper caseMapper;

    /**
     * 批量记录命中流水（逐条容错，任何单条失败不影响其他命中）
     * @param hits 检索命中项列表（docId 为空表示无法关联文档，跳过）
     * @param query 触发检索的问题
     * @param hitSource 命中来源（ASK / TEST / RETRIEVE）
     * @param channel 渠道标识（用户名或测试标识，可空）
     * @return 成功写入的流水条数
     */
    public int recordHits(List<RetrievalHitResponse> hits, String query, String hitSource, String channel) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        int recorded = 0;
        for (RetrievalHitResponse hit : hits) {
            if (hit == null || hit.getDocId() == null) {
                continue; // 无 docId 的命中无法关联文档，跳过
            }
            try {
                insertHit(hit, query, hitSource, channel);
                recorded++;
            } catch (Exception e) {
                log.warn("记录命中流水失败: docId={}, 原因={}", hit.getDocId(), e.getMessage());
            }
        }
        return recorded;
    }

    /**
     * 单条命中落库：
     * 1) 写 knowledge_hit_log 流水
     * 2) knowledge_document.hit_count + 1
     * 3) 若文档属于 CASE 层，解析 case:{id} 同步累加 knowledge_case.cited_count
     */
    private void insertHit(RetrievalHitResponse hit, String query, String hitSource, String channel) {
        Long docId = hit.getDocId();

        // 1. 写命中流水
        KnowledgeHitLogEntity entity = new KnowledgeHitLogEntity();
        entity.setDocId(docId);
        entity.setQuery(truncate(query, 500));
        entity.setHitSource(hitSource);
        entity.setChannel(truncate(channel, 64));
        entity.setCreatedAt(LocalDateTime.now());
        hitLogMapper.insert(entity);

        // 2. 文档命中数 +1（空安全累加）
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, docId)
                .setSql("hit_count = COALESCE(hit_count, 0) + 1"));

        // 3. CASE 层文档同步累加案例引用数
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc != null && "CASE".equalsIgnoreCase(doc.getLayer())) {
            Long caseId = parseCaseId(doc.getSource());
            if (caseId != null) {
                caseMapper.update(null, new LambdaUpdateWrapper<KnowledgeCaseEntity>()
                        .eq(KnowledgeCaseEntity::getId, caseId)
                        .setSql("cited_count = COALESCE(cited_count, 0) + 1"));
            }
        }
    }

    /**
     * 从文档 source（case:{id}）解析案例 ID；非 case: 前缀返回 null
     */
    private Long parseCaseId(String source) {
        if (!StringUtils.hasText(source) || !source.startsWith(CASE_SOURCE_PREFIX)) {
            return null;
        }
        String idPart = source.substring(CASE_SOURCE_PREFIX.length()).trim();
        if (idPart.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 分页查询单篇文档的命中流水（P2：单篇命中反查）
     * @param docId 文档 ID
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 命中流水分页结果（按命中时间倒序）
     */
    public PageResult<KnowledgeHitLogResponse> listByDocument(Long docId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<KnowledgeHitLogEntity> result = hitLogMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<KnowledgeHitLogEntity>()
                        .eq(KnowledgeHitLogEntity::getDocId, docId)
                        .orderByDesc(KnowledgeHitLogEntity::getCreatedAt));

        List<KnowledgeHitLogResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .toList();
        return PageResult.<KnowledgeHitLogResponse>builder()
                .total(result.getTotal())
                .records(records)
                .build();
    }

    /**
     * 案例引用统计（P2：案例引用统计）
     * 通过 source=case:{caseId} 定位案例文档，统计其命中总数并返回最近命中流水。
     * @param caseId 案例 ID
     * @return 引用统计响应；案例文档不存在时返回 totalHits=0 与空列表
     */
    public KnowledgeCaseReferenceResponse listCaseReferences(Long caseId) {
        KnowledgeCaseReferenceResponse response = new KnowledgeCaseReferenceResponse();
        response.setCaseId(caseId);

        KnowledgeDocumentEntity caseDoc = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getSource, CASE_SOURCE_PREFIX + caseId)
                        .eq(KnowledgeDocumentEntity::getLayer, "CASE")
                        .last("LIMIT 1"));
        if (caseDoc == null) {
            response.setDocId(null);
            response.setTotalHits(0L);
            response.setRecentHits(List.of());
            return response;
        }

        Long docId = caseDoc.getId();
        response.setDocId(docId);
        response.setTotalHits(hitLogMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeHitLogEntity>()
                        .eq(KnowledgeHitLogEntity::getDocId, docId)));

        List<KnowledgeHitLogResponse> recent = hitLogMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeHitLogEntity>()
                                .eq(KnowledgeHitLogEntity::getDocId, docId)
                                .orderByDesc(KnowledgeHitLogEntity::getCreatedAt)
                                .last("LIMIT 10"))
                .stream()
                .map(this::toResponse)
                .toList();
        response.setRecentHits(recent);
        return response;
    }

    /**
     * 命中流水实体转响应 DTO
     * @param entity 流水实体
     * @return 流水响应
     */
    private KnowledgeHitLogResponse toResponse(KnowledgeHitLogEntity entity) {
        KnowledgeHitLogResponse response = new KnowledgeHitLogResponse();
        response.setId(entity.getId());
        response.setDocId(entity.getDocId());
        response.setQuery(entity.getQuery());
        response.setHitSource(entity.getHitSource());
        response.setChannel(entity.getChannel());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
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
}
