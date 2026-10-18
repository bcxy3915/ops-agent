package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.KnowledgeVersionResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentVersionEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识文档版本历史与回滚服务（P2）
 * 负责版本快照的写入、版本列表查询与版本归属校验；回滚动作由 KnowledgeDocumentService 编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeVersionService {

    /** 在线编写产生的初始版本 */
    public static final String REASON_ONLINE_CREATE = "ONLINE_CREATE";

    /** 编辑文档产生的新版本 */
    public static final String REASON_UPDATE = "UPDATE";

    /** 回滚后现场备份 */
    public static final String REASON_ROLLBACK_BACKUP = "ROLLBACK_BACKUP";

    private final KnowledgeVersionMapper versionMapper;

    /**
     * 记录一条版本快照（同文档版本号取最大值 +1）
     * @param docId 文档 ID
     * @param content 内容快照
     * @param changedBy 变更人
     * @param changeReason 变更原因
     * @return 本次写入的版本号
     */
    @Transactional
    public int recordSnapshot(Long docId, String content, String changedBy, String changeReason) {
        if (docId == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID, "文档 ID 不能为空");
        }
        Integer maxVersion = versionMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                                .eq(KnowledgeDocumentVersionEntity::getDocId, docId)
                                .orderByDesc(KnowledgeDocumentVersionEntity::getVersion)
                                .last("LIMIT 1"))
                .stream()
                .map(KnowledgeDocumentVersionEntity::getVersion)
                .findFirst()
                .orElse(0);

        KnowledgeDocumentVersionEntity snapshot = new KnowledgeDocumentVersionEntity();
        snapshot.setDocId(docId);
        snapshot.setVersion(maxVersion + 1);
        snapshot.setContentSnapshot(content);
        snapshot.setChangedBy(changedBy);
        snapshot.setChangeReason(changeReason);
        snapshot.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(snapshot);
        log.info("知识文档版本快照记录: docId={}, version={}, reason={}", docId, snapshot.getVersion(), changeReason);
        return snapshot.getVersion();
    }

    /**
     * 查询指定文档的全部版本历史（按版本号降序）
     * @param docId 文档 ID
     * @return 版本列表
     */
    public List<KnowledgeVersionResponse> listVersions(Long docId) {
        return versionMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                                .eq(KnowledgeDocumentVersionEntity::getDocId, docId)
                                .orderByDesc(KnowledgeDocumentVersionEntity::getVersion))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 校验并返回一条版本记录（必须属于指定文档）
     * @param docId 文档 ID
     * @param versionId 版本记录 ID
     * @return 版本实体
     */
    public KnowledgeDocumentVersionEntity requireVersion(Long docId, Long versionId) {
        if (versionId == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND, "版本 ID 不能为空");
        }
        KnowledgeDocumentVersionEntity version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND, "知识文档版本不存在");
        }
        if (!java.util.Objects.equals(version.getDocId(), docId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND, "版本不属于该文档");
        }
        return version;
    }

    /**
     * 实体转响应 DTO
     * @param entity 版本实体
     * @return 版本响应
     */
    private KnowledgeVersionResponse toResponse(KnowledgeDocumentVersionEntity entity) {
        KnowledgeVersionResponse response = new KnowledgeVersionResponse();
        response.setId(entity.getId());
        response.setVersion(entity.getVersion());
        response.setContentSnapshot(entity.getContentSnapshot());
        response.setChangedBy(entity.getChangedBy());
        response.setChangeReason(entity.getChangeReason());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
