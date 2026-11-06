package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.KnowledgeChunkResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeIndexStatusResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeOnlineCreateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeReviewRequest;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentVersionEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeIndexTaskEntity;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategy;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategyFactory;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.rbac.entity.TenantEntity;
import com.example.opsaiagent.rbac.service.DeptService;
import com.example.opsaiagent.rbac.service.TenantService;
import com.example.opsaiagent.retrieval.KnowledgeIndexUpdatedEvent;
import com.example.opsaiagent.security.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识文档服务（T18）
 * 文档 CRUD、上传解析、审核发布（通过后分块 + 向量化入库）、编辑、删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    /** 上传文档只允许私有层（BUILTIN 内置 / CASE 案例由其他流程生成） */
    public static final String LAYER_PRIVATE = "PRIVATE";
    /** 官方内置知识层级（由 DocumentLoader 从 classpath 同步，业务侧只读） */
    public static final String LAYER_BUILTIN = "BUILTIN";
    /** 审核通过后的信任状态 */
    public static final String TRUST_VERIFIED = "VERIFIED";
    /** 待审核 */
    public static final String TRUST_PENDING = "PENDING";
    /** 复核到期（自动超期判定标记） */
    public static final String TRUST_EXPIRED = "EXPIRED";
    /** 上传文档向量在 vector_store.metadata.source 的前缀 */
    public static final String UPLOAD_VECTOR_SOURCE_PREFIX = "upload:";
    /** 在线编写文档 source 前缀 */
    public static final String ONLINE_SOURCE_PREFIX = "online:";

    /** 可见性：个人 */
    public static final String VISIBILITY_PERSONAL = "PERSONAL";
    /** 可见性：部门 */
    public static final String VISIBILITY_DEPT = "DEPT";
    /** 可见性：企业 */
    public static final String VISIBILITY_TENANT = "TENANT";

    /** 审核状态：草稿 */
    public static final String REVIEW_DRAFT = "DRAFT";
    /** 审核状态：待部门审核 */
    public static final String REVIEW_PENDING_DEPT = "PENDING_DEPT";
    /** 审核状态：待企业审核 */
    public static final String REVIEW_PENDING_TENANT = "PENDING_TENANT";
    /** 审核状态：已通过 */
    public static final String REVIEW_APPROVED = "APPROVED";
    /** 审核状态：已驳回 */
    public static final String REVIEW_REJECTED = "REJECTED";

    /** 审核层级：无 */
    public static final String REVIEW_LEVEL_NONE = "NONE";
    /** 审核层级：部门 */
    public static final String REVIEW_LEVEL_DEPT = "DEPT";
    /** 审核层级：企业 */
    public static final String REVIEW_LEVEL_TENANT = "TENANT";

    /** 审核模式：仅部门 */
    public static final String REVIEW_MODE_DEPT_ONLY = "DEPT_ONLY";
    /** 审核模式：部门 + 企业 */
    public static final String REVIEW_MODE_DEPT_AND_TENANT = "DEPT_AND_TENANT";
    /** 审核模式：无需审核 */
    public static final String REVIEW_MODE_NONE = "NONE";

    /** 索引发布状态：未发布（草稿 / 待审核 / 驳回 / 内容变更后） */
    public static final String PUBLISH_UNPUBLISHED = "UNPUBLISHED";
    /** 索引发布状态：入库中（审核已通过，后台索引任务执行中） */
    public static final String PUBLISH_BUILDING = "BUILDING";
    /** 索引发布状态：入库失败（可重试） */
    public static final String PUBLISH_FAILED = "FAILED";
    /** 索引发布状态：已生效（分块与向量已完整写入，可被检索命中） */
    public static final String PUBLISH_PUBLISHED = "PUBLISHED";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final VectorIndexer vectorIndexer;
    private final DocumentParserStrategyFactory parserStrategyFactory;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeVersionService versionService;
    private final TenantService tenantService;
    private final DeptService deptService;
    private final KnowledgeQueryScope queryScope;
    private final KnowledgeIndexTaskService indexTaskService;
    private final ApplicationEventPublisher eventPublisher;

    /** 上传文件大小上限（MB） */
    @Value("${ops-agent.knowledge.upload.max-size-mb:50}")
    private long maxUploadMb = 50;

    /**
     * 分页查询文档列表
     * @param keyword 关键词（标题/来源模糊匹配）
     * @param layer 知识层级（BUILTIN/PRIVATE/CASE）
     * @param trustStatus 信任状态
     * @param service 适用服务（service_ids JSON 数组包含匹配）
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 分页结果
     */
    public PageResult<KnowledgeDocumentResponse> list(String keyword, String layer, String trustStatus,
                                                      String service, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = queryScope.current().documents();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(KnowledgeDocumentEntity::getTitle, keyword)
                    .or().like(KnowledgeDocumentEntity::getSource, keyword));
        }
        if (StringUtils.hasText(layer)) {
            wrapper.eq(KnowledgeDocumentEntity::getLayer, layer);
        }
        if (StringUtils.hasText(trustStatus)) {
            wrapper.eq(KnowledgeDocumentEntity::getTrustStatus, trustStatus);
        }
        if (StringUtils.hasText(service)) {
            wrapper.like(KnowledgeDocumentEntity::getServiceIds, "\"" + service + "\"");
        }
        wrapper.orderByDesc(KnowledgeDocumentEntity::getUpdatedAt);

        Page<KnowledgeDocumentEntity> result = documentMapper.selectPage(
                new Page<>(safePage, safeSize), wrapper);

        List<KnowledgeDocumentResponse> records = result.getRecords().stream()
                .map(e -> toResponse(e, false))
                .toList();
        return PageResult.<KnowledgeDocumentResponse>builder()
                .records(records)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .pages(result.getPages())
                .build();
    }

    /**
     * 查询文档详情（含分块列表）
     * @param id 文档 ID
     * @return 文档详情
     */
    public KnowledgeDocumentResponse getById(Long id) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        if (!queryScope.current().canAccess(entity)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在: " + id);
        }
        return toResponse(entity, true);
    }

    /**
     * 上传并解析文档（保存为待审核，不向量化）
     * @param file 上传文件（md/pdf/txt/docx）
     * @param title 文档标题
     * @param layer 知识层级（必须为 PRIVATE）
     * @param owner 负责人
     * @param serviceIds 适用服务
     * @param tags 标签
     * @return 文档响应
     */
    @Transactional
    public KnowledgeDocumentResponse upload(MultipartFile file, String title, String layer,
                                            String owner, List<String> serviceIds, List<String> tags) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID, "上传文件为空");
        }
        long maxBytes = maxUploadMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID,
                    "上传文件超过大小限制（最大 " + maxUploadMb + "MB）");
        }
        if (!LAYER_PRIVATE.equals(layer)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID,
                    "上传文档层级只能为 PRIVATE（内置/案例由系统生成）");
        }
        ParsedDocument parsed = parse(file);
        if (!StringUtils.hasText(parsed.rawContent())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID, "文档内容为空或解析失败");
        }

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setTitle(StringUtils.hasText(title) ? title.trim() : parsed.fileName());
        entity.setDocType(parsed.docType());
        entity.setLayer(LAYER_PRIVATE);
        entity.setTrustStatus(TRUST_PENDING);
        applyDraftScope(entity);
        entity.setVersion(1);
        entity.setOwner(resolveOwner(owner));
        entity.setServiceIds(toJson(serviceIds));
        entity.setTags(toJson(tags));
        entity.setHitCount(0L);
        entity.setSource(parsed.fileName());
        entity.setFilePath("upload:" + parsed.fileName());
        entity.setRawContent(parsed.rawContent());
        entity.setChunkCount(0);
        entity.setPublishStatus(PUBLISH_UNPUBLISHED);
        entity.setSubmittedAt(LocalDateTime.now());
        documentMapper.insert(entity);
        log.info("知识文档上传成功: id={}, title={}, type={}", entity.getId(), entity.getTitle(), parsed.docType());
        return toResponse(entity, false);
    }

    /**
     * 在线编写知识文档（P1）
     * 与上传文档同流程：先保存为待审核，审核通过后分块向量化入库。
     * @param request 在线编写请求（title/content 必填）
     * @return 文档响应
     */
    @Transactional
    public KnowledgeDocumentResponse onlineCreate(KnowledgeOnlineCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getTitle()) || !StringUtils.hasText(request.getContent())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID, "标题和内容不能为空");
        }
        String title = request.getTitle().trim();
        vectorIndexer.checkTextLength(request.getContent());

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setTitle(title);
        entity.setDocType("MARKDOWN");
        entity.setLayer(LAYER_PRIVATE);
        entity.setTrustStatus(TRUST_PENDING);
        applyDraftScope(entity);
        entity.setVersion(1);
        entity.setOwner(resolveOwner(request.getOwner()));
        entity.setServiceIds(toJson(request.getServiceIds()));
        entity.setTags(toJson(request.getTags()));
        entity.setHitCount(0L);
        entity.setSource(ONLINE_SOURCE_PREFIX + title);
        entity.setFilePath(ONLINE_SOURCE_PREFIX + title);
        entity.setRawContent(request.getContent());
        entity.setChunkCount(0);
        entity.setPublishStatus(PUBLISH_UNPUBLISHED);
        entity.setEffectiveAt(LocalDateTime.now());
        entity.setSubmittedAt(LocalDateTime.now());
        documentMapper.insert(entity);
        // 记录初始版本快照，便于后续编辑回滚
        versionService.recordSnapshot(entity.getId(), request.getContent(),
                StringUtils.hasText(request.getOwner()) ? request.getOwner() : currentUsername(),
                KnowledgeVersionService.REASON_ONLINE_CREATE);
        log.info("在线编写知识文档成功: id={}, title={}", entity.getId(), entity.getTitle());
        return toResponse(entity, false);
    }

    /**
     * 获取当前登录用户名，未登录时返回 system
     * @return 用户名
     */
    private String currentUsername() {
        String username = SecurityUtils.getCurrentUsername();
        return StringUtils.hasText(username) ? username : "system";
    }

    /**
     * 自动超期判定（P1）
     * 扫描所有已复核到期（review_at < now）的 VERIFIED 文档，标记为 EXPIRED。
     * @return 被标记为过期的文档数量
     */
    @Transactional
    public int expireOverdueDocuments() {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTrustStatus, TRUST_VERIFIED)
                .isNotNull(KnowledgeDocumentEntity::getReviewAt)
                .lt(KnowledgeDocumentEntity::getReviewAt, now);
        // 租户管理员只能判定本租户；平台上下文才允许全量扫描
        String currentTenant = SecurityUtils.getCurrentTenantId();
        if (StringUtils.hasText(currentTenant) && !"0".equals(currentTenant)) {
            wrapper.eq(KnowledgeDocumentEntity::getTenantId, currentTenant);
        }
        List<KnowledgeDocumentEntity> overdue = documentMapper.selectList(wrapper);
        int count = 0;
        for (KnowledgeDocumentEntity doc : overdue) {
            doc.setTrustStatus(TRUST_EXPIRED);
            documentMapper.updateById(doc);
            count++;
        }
        if (count > 0) {
            log.info("自动超期判定完成: 过期文档 {} 篇", count);
        }
        return count;
    }

    /**
     * 发布审核
     * @param id 文档 ID
     * @param approved true=通过（向量化入库）；false=驳回（保持待处理）
     * @return 文档响应
     */
    @Transactional
    public KnowledgeDocumentResponse publish(Long id, boolean approved) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        KnowledgeReviewRequest request = new KnowledgeReviewRequest();
        request.setApproved(approved);
        request.setComment(approved ? "发布审核通过" : "发布审核驳回");
        if (REVIEW_PENDING_DEPT.equals(entity.getReviewStatus())) {
            request.setLevel(REVIEW_LEVEL_DEPT);
            return review(id, request);
        }
        if (REVIEW_PENDING_TENANT.equals(entity.getReviewStatus())) {
            request.setLevel(REVIEW_LEVEL_TENANT);
            return review(id, request);
        }
        throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID,
                "当前文档不处于待审核状态");
    }

    /**
     * 直接发布：管理员上传后跳过审核流程，立即置为已通过并交给后台异步入库。
     *
     * 仅允许对尚未进入审核流的草稿 / 已驳回文档使用，避免绕过审核人对待审核文档的裁定。
     * 权限由 Controller 的 {@code knowledge:publish} 限定，数据范围沿用作者或知识管理员校验。
     *
     * @param id 文档 ID
     * @return 文档响应（reviewStatus=APPROVED、publishStatus=BUILDING）
     */
    @Transactional
    public KnowledgeDocumentResponse publishDirect(Long id) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        checkOwnerOrAdmin(entity);
        if (!REVIEW_DRAFT.equals(entity.getReviewStatus())
                && !REVIEW_REJECTED.equals(entity.getReviewStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID,
                    "仅草稿或已驳回文档可直接发布，当前状态: " + entity.getReviewStatus());
        }
        approveDocument(entity, REVIEW_LEVEL_TENANT, "直接发布");
        log.info("知识文档直接发布并进入后台入库: id={}, version={}", id, entity.getVersion());
        return toResponse(requireDocument(id), true);
    }

    /**
     * 提交知识文档审核。
     *
     * 仅作者或知识管理员可提交；按租户审核模式进入部门审核，
     * 审核模式为 NONE 时直接通过并入库。
     *
     * @param id 文档 ID
     * @return 文档响应
     */
    @Transactional
    public KnowledgeDocumentResponse submitForReview(Long id) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        checkOwnerOrAdmin(entity);
        if (!REVIEW_DRAFT.equals(entity.getReviewStatus())
                && !REVIEW_REJECTED.equals(entity.getReviewStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID,
                    "仅草稿或已驳回文档可以提交审核，当前状态: " + entity.getReviewStatus());
        }

        String mode = reviewMode(entity.getTenantId());
        if (REVIEW_MODE_NONE.equals(mode)) {
            approveDocument(entity, REVIEW_LEVEL_TENANT, "租户无需审核，自动通过");
        } else {
            entity.setReviewStatus(REVIEW_PENDING_DEPT);
            entity.setReviewLevel(REVIEW_LEVEL_DEPT);
            entity.setVisibility(VISIBILITY_DEPT);
            entity.setSubmittedAt(LocalDateTime.now());
            entity.setReviewedBy(null);
            entity.setReviewedAt(null);
            entity.setDeptReviewedBy(null);
            entity.setDeptReviewedAt(null);
            entity.setReviewNote(null);
            documentMapper.updateById(entity);
        }
        log.info("知识文档提交审核: id={}, mode={}, status={}", id, mode, entity.getReviewStatus());
        return toResponse(requireDocument(id), true);
    }

    /**
     * 审核知识文档。
     *
     * <p>部门审核通过后：DEPT_ONLY 直接发布；DEPT_AND_TENANT 继续流转企业审核。
     * 企业审核通过后可见性提升为 TENANT。驳回一律退回 PERSONAL。</p>
     *
     * @param id      文档 ID
     * @param request 审核请求
     * @return 文档响应
     */
    @Transactional
    public KnowledgeDocumentResponse review(Long id, KnowledgeReviewRequest request) {
        if (request == null || !StringUtils.hasText(request.getLevel())
                || request.getApproved() == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID, "审核参数不完整");
        }
        KnowledgeDocumentEntity entity = requireDocument(id);
        String note = request.getComment();
        boolean approved = Boolean.TRUE.equals(request.getApproved());

        if (REVIEW_LEVEL_DEPT.equals(request.getLevel())) {
            if (!REVIEW_PENDING_DEPT.equals(entity.getReviewStatus())
                    || !REVIEW_LEVEL_DEPT.equals(entity.getReviewLevel())) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID,
                        "当前文档不处于待部门审核状态");
            }
            checkDeptReviewer(entity);
            checkFourEyes(entity);
            LocalDateTime deptNow = LocalDateTime.now();
            entity.setDeptReviewedBy(currentUsername());
            entity.setDeptReviewedAt(deptNow);
            if (!approved) {
                rejectDocument(entity, note);
                log.info("知识文档部门审核驳回: id={}", id);
            } else if (REVIEW_MODE_DEPT_AND_TENANT.equals(reviewMode(entity.getTenantId()))) {
                entity.setReviewStatus(REVIEW_PENDING_TENANT);
                entity.setReviewLevel(REVIEW_LEVEL_TENANT);
                entity.setReviewedBy(currentUsername());
                entity.setReviewedAt(deptNow);
                entity.setReviewNote(note);
                documentMapper.updateById(entity);
                log.info("知识文档部门审核通过，转企业审核: id={}", id);
            } else {
                approveDocument(entity, REVIEW_LEVEL_DEPT, note);
                log.info("知识文档部门审核通过: id={}", id);
            }
        } else if (REVIEW_LEVEL_TENANT.equals(request.getLevel())) {
            if (!REVIEW_PENDING_TENANT.equals(entity.getReviewStatus())
                    || !REVIEW_LEVEL_TENANT.equals(entity.getReviewLevel())) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID,
                        "当前文档不处于待企业审核状态");
            }
            checkTenantReviewer(entity);
            checkFourEyes(entity);
            if (!approved) {
                rejectDocument(entity, note);
                log.info("知识文档企业审核驳回: id={}", id);
            } else {
                approveDocument(entity, REVIEW_LEVEL_TENANT, note);
                log.info("知识文档企业审核通过: id={}", id);
            }
        } else {
            throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID,
                    "审核层级不合法: " + request.getLevel());
        }
        return toResponse(requireDocument(id), true);
    }

    /**
     * 设置新文档的默认租户/部门/可见性/审核状态。
     *
     * @param entity 文档实体
     */
    private void applyDraftScope(KnowledgeDocumentEntity entity) {
        entity.setTenantId(currentTenantId());
        entity.setDeptId(currentDeptId());
        entity.setVisibility(VISIBILITY_PERSONAL);
        entity.setReviewStatus(REVIEW_DRAFT);
        entity.setReviewLevel(REVIEW_LEVEL_NONE);
    }

    /**
     * 解析文档负责人：登录用户优先，未登录时保留请求值。
     *
     * @param requested 请求中的负责人
     * @return 负责人
     */
    private String resolveOwner(String requested) {
        String current = SecurityUtils.getCurrentUsername();
        if (StringUtils.hasText(current)) {
            return current;
        }
        return StringUtils.hasText(requested) ? requested : "system";
    }

    /**
     * 当前租户；无上下文时回退引导租户，兼容旧调用。
     *
     * @return 租户 ID
     */
    private String currentTenantId() {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return StringUtils.hasText(tenantId) ? tenantId : "t-default";
    }

    /**
     * 当前部门。
     *
     * @return 部门 ID；未设置返回 null
     */
    private Long currentDeptId() {
        return SecurityUtils.getCurrentDeptId();
    }

    /**
     * 查询租户知识审核模式。
     *
     * @param tenantId 租户 ID
     * @return DEPT_ONLY / DEPT_AND_TENANT / NONE
     */
    private String reviewMode(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return REVIEW_MODE_DEPT_ONLY;
        }
        return tenantService.findById(tenantId)
                .map(TenantEntity::getKnowledgeReviewMode)
                .filter(StringUtils::hasText)
                .orElse(REVIEW_MODE_DEPT_ONLY);
    }

    /**
     * 校验当前用户是作者或知识管理员。
     *
     * @param entity 文档
     */
    private void checkOwnerOrAdmin(KnowledgeDocumentEntity entity) {
        String current = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(current)) {
            // 单元测试 / 系统任务无登录上下文时不做作者校验
            return;
        }
        String currentTenant = SecurityUtils.getCurrentTenantId();
        if (StringUtils.hasText(currentTenant) && !"0".equals(currentTenant)
                && !currentTenant.equals(entity.getTenantId())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND,
                    "知识文档不存在: " + entity.getId());
        }
        if (current.equals(entity.getOwner()) || SecurityUtils.hasPermission("knowledge:admin")) {
            return;
        }
        throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                "无权操作该知识文档: " + entity.getId());
    }

    /**
     * 入库中禁止写操作。
     *
     * 索引任务执行期间修改、删除或回滚会让任务中的文档版本失效，
     * 甚至产生新旧向量重叠，因此 BUILDING 状态只允许查看，待任务落定后再操作。
     *
     * @param entity 文档
     */
    private void ensureNotBuilding(KnowledgeDocumentEntity entity) {
        if (PUBLISH_BUILDING.equals(entity.getPublishStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID,
                    "文档正在后台入库，暂不支持修改或删除，请等待入库完成");
        }
    }

    /**
     * 官方内置知识只读。
     *
     * <p>内置文档由 DocumentLoader 从 classpath 同步，业务侧修改/删除/重建索引都会在下次 reload 被覆盖，
     * 且 reindex 会按文档 ID 生成 upload: source，造成内置向量重复，因此统一拒绝。</p>
     *
     * @param entity 文档
     */
    private void ensureNotBuiltin(KnowledgeDocumentEntity entity) {
        if (LAYER_BUILTIN.equals(entity.getLayer())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "官方内置知识由系统同步维护，不支持修改、删除或重建索引");
        }
    }

    /**
     * 校验部门审核人：本部门或上级部门（子树包含文档部门）。
     *
     * @param entity 文档
     */
    private void checkDeptReviewer(KnowledgeDocumentEntity entity) {
        if (!SecurityUtils.hasRole("DEPT_ADMIN")
                && !SecurityUtils.hasRole("TENANT_ADMIN")
                && !SecurityUtils.hasPermission("knowledge:admin")) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "仅部门管理员可执行部门审核");
        }
        // 审核人必须属于文档租户，避免 knowledge:admin 跨租户审核
        String currentTenant = SecurityUtils.getCurrentTenantId();
        if (StringUtils.hasText(currentTenant) && !currentTenant.equals(entity.getTenantId())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "无权审核该租户知识文档: " + entity.getId());
        }
        if (entity.getDeptId() == null) {
            // 无部门归属的文档（如租户级用户上传）只能由知识管理员兜底审核
            if (SecurityUtils.hasPermission("knowledge:admin")) {
                return;
            }
            throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID, "文档未归属部门，无法部门审核");
        }
        Long currentDept = currentDeptId();
        boolean allowed = currentDept != null
                && deptService.subtreeIds(entity.getTenantId(), currentDept).contains(entity.getDeptId());
        if (!allowed && SecurityUtils.hasPermission("knowledge:admin")) {
            allowed = true;
        }
        if (!allowed) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "无权审核该部门知识文档: " + entity.getId());
        }
    }

    /**
     * 校验企业审核人属于文档所属租户。
     *
     * @param entity 文档
     */
    private void checkTenantReviewer(KnowledgeDocumentEntity entity) {
        if (!SecurityUtils.hasRole("TENANT_ADMIN")
                && !SecurityUtils.hasPermission("knowledge:admin")) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "仅企业管理员可执行企业审核");
        }
        String currentTenant = SecurityUtils.getCurrentTenantId();
        if (!StringUtils.hasText(currentTenant) || !currentTenant.equals(entity.getTenantId())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "无权审核该企业知识文档: " + entity.getId());
        }
        // 部门审核人与企业审核人必须分离，避免同一人完成两级审核
        if (currentUsername().equals(entity.getReviewedBy())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "部门审核人与企业审核人不能为同一人");
        }
    }

    /**
     * 四眼原则：审核人不能是作者。
     *
     * @param entity 文档
     */
    private void checkFourEyes(KnowledgeDocumentEntity entity) {
        boolean enabled = tenantService.findById(entity.getTenantId())
                .map(TenantEntity::getReviewFourEyes)
                .orElse(Boolean.FALSE);
        if (enabled && currentUsername().equals(entity.getOwner())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                    "四眼原则要求审核人不能是作者");
        }
    }

    /**
     * 审核通过：更新可见性/审核字段，并把索引任务交给后台异步执行。
     *
     * <p>审核结论立即生效（review_status=APPROVED），可检索性由 publish_status 表达：
     * 入队后置 BUILDING，由后台任务成功时改 PUBLISHED、失败时改 FAILED。
     * 审核通过不预删旧索引，新索引成功后整体覆盖，索引失败时旧内容仍可命中。</p>
     *
     * @param entity 文档
     * @param level  审核层级
     * @param note   审核意见
     */
    private void approveDocument(KnowledgeDocumentEntity entity, String level, String note) {
        LocalDateTime now = LocalDateTime.now();
        entity.setTrustStatus(TRUST_VERIFIED);
        entity.setReviewStatus(REVIEW_APPROVED);
        entity.setReviewLevel(level);
        // 无部门归属的文档没有 DEPT 语义，回退为企业可见，否则审核通过后两条检索路径都命中不到
        boolean tenantVisible = REVIEW_LEVEL_TENANT.equals(level) || entity.getDeptId() == null;
        entity.setVisibility(tenantVisible ? VISIBILITY_TENANT : VISIBILITY_DEPT);
        entity.setReviewedBy(currentUsername());
        entity.setReviewedAt(now);
        entity.setLastReviewAt(now);
        entity.setReviewNote(note);
        entity.setPublishStatus(PUBLISH_BUILDING);
        entity.setPublishError(null);
        entity.setPublishedAt(null);
        documentMapper.updateById(entity);
        // 分块与向量化含外部 embedding 调用、耗时不可控，交给后台任务在独立事务中执行，
        // 避免长时间占用数据库事务与连接；审核结果立即生效，索引稍后完成
        indexTaskService.enqueue(entity.getId(), entity.getVersion(), vectorSourceOf(entity));
    }

    /**
     * 审核驳回：退回个人可见，并删除已入库向量（向量 metadata 是审核时的快照，
     * 仅改 DB 不会让旧向量失效，必须物理删除才能确保驳回内容不再被检索命中）。
     *
     * @param entity 文档
     * @param note   驳回原因
     */
    private void rejectDocument(KnowledgeDocumentEntity entity, String note) {
        entity.setTrustStatus(TRUST_PENDING);
        entity.setReviewStatus(REVIEW_REJECTED);
        entity.setReviewLevel(REVIEW_LEVEL_NONE);
        entity.setVisibility(VISIBILITY_PERSONAL);
        entity.setReviewedBy(currentUsername());
        entity.setReviewedAt(LocalDateTime.now());
        entity.setLastReviewAt(LocalDateTime.now());
        entity.setReviewNote(note);
        entity.setPublishStatus(PUBLISH_UNPUBLISHED);
        entity.setPublishError(null);
        entity.setPublishedAt(null);
        documentMapper.updateById(entity);
        deleteVectors(entity);
    }

    /**
     * 编辑文档（元数据；rawContent 变更时重新分块 + 向量化，版本 +1，状态重置为待审核）
     * @param id 文档 ID
     * @param request 编辑请求
     * @return 文档响应
     */
    @Transactional
    public KnowledgeDocumentResponse update(Long id, KnowledgeDocumentUpdateRequest request) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        checkOwnerOrAdmin(entity);
        ensureNotBuilding(entity);
        ensureNotBuiltin(entity);
        boolean contentChanged = request.getRawContent() != null
                && !Objects.equals(entity.getRawContent(), request.getRawContent());

        entity.setTitle(request.getTitle().trim());
        // owner 仅上传时由服务端固化，编辑不允许转赠文档归属
        if (request.getServiceIds() != null) {
            entity.setServiceIds(toJson(request.getServiceIds()));
        }
        if (request.getTags() != null) {
            entity.setTags(toJson(request.getTags()));
        }

        if (contentChanged) {
            validateRawContent(request.getRawContent());
            entity.setRawContent(request.getRawContent());
            entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
            entity.setTrustStatus(TRUST_PENDING);
            entity.setReviewStatus(REVIEW_DRAFT);
            entity.setReviewLevel(REVIEW_LEVEL_NONE);
            entity.setVisibility(VISIBILITY_PERSONAL);
            entity.setReviewedBy(null);
            entity.setReviewedAt(null);
            entity.setDeptReviewedBy(null);
            entity.setDeptReviewedAt(null);
            entity.setReviewNote(null);
            entity.setPublishStatus(PUBLISH_UNPUBLISHED);
            entity.setPublishError(null);
            entity.setPublishedAt(null);
            entity.setSubmittedAt(LocalDateTime.now());
            // 内容变更后必须重新审核，先下线旧分块与向量，审核通过后再入库
            deleteChunksAndVectors(entity);
            documentMapper.updateById(entity);
            // 记录变更后的版本快照，支持历史回滚
            versionService.recordSnapshot(id, request.getRawContent(), currentUsername(),
                    KnowledgeVersionService.REASON_UPDATE);
        } else {
            documentMapper.updateById(entity);
        }
        return toResponse(requireDocument(id), true);
    }

    /**
     * 回滚文档到指定历史版本（P2）
     * 回滚前先把当前内容备份为一条历史快照，再以目标版本内容覆盖文档；
     * 文档版本号 +1、状态重置为待审核，并重新分块向量化入库。
     * @param docId 文档 ID
     * @param versionId 目标版本记录 ID
     * @return 回滚后的文档响应
     */
    @Transactional
    public KnowledgeDocumentResponse rollback(Long docId, Long versionId) {
        KnowledgeDocumentVersionEntity target = versionService.requireVersion(docId, versionId);
        KnowledgeDocumentEntity entity = requireDocument(docId);
        checkOwnerOrAdmin(entity);
        ensureNotBuilding(entity);
        ensureNotBuiltin(entity);
        validateRawContent(target.getContentSnapshot());

        // 1. 备份当前内容为历史版本，保留回滚现场
        versionService.recordSnapshot(docId, entity.getRawContent(), currentUsername(),
                KnowledgeVersionService.REASON_ROLLBACK_BACKUP);

        // 2. 用目标版本内容覆盖文档，回滚后需重新审核
        entity.setRawContent(target.getContentSnapshot());
        entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
        entity.setTrustStatus(TRUST_PENDING);
        entity.setReviewStatus(REVIEW_DRAFT);
        entity.setReviewLevel(REVIEW_LEVEL_NONE);
        entity.setVisibility(VISIBILITY_PERSONAL);
        entity.setReviewedBy(null);
        entity.setReviewedAt(null);
        entity.setDeptReviewedBy(null);
        entity.setDeptReviewedAt(null);
        entity.setReviewNote(null);
        entity.setPublishStatus(PUBLISH_UNPUBLISHED);
        entity.setPublishError(null);
        entity.setPublishedAt(null);
        entity.setSubmittedAt(LocalDateTime.now());

        // 3. 下线旧分块与向量，审核通过后再入库
        deleteChunksAndVectors(entity);
        documentMapper.updateById(entity);
        log.info("知识文档版本回滚: docId={}, versionId={}, version={}", docId, versionId, target.getVersion());
        return toResponse(requireDocument(docId), true);
    }

    /**
     * 删除文档（含分块与向量）
     * @param id 文档 ID
     */
    @Transactional
    public void delete(Long id) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        checkOwnerOrAdmin(entity);
        ensureNotBuilding(entity);
        ensureNotBuiltin(entity);
        deleteChunksAndVectors(entity);
        documentMapper.deleteById(id);
        log.info("知识文档已删除: id={}, title={}", entity.getId(), entity.getTitle());
    }

    /**
     * 重建文档索引：仅已通过审核且已发布/已失败的文档可重试。
     *
     * <p>审核结论不变，只把 publish_status 置回 BUILDING 并重新入队，
     * 便于索引失败后由作者或审核人手动重试，无需重新审核。</p>
     *
     * @param id 文档 ID
     * @return 文档响应（publishStatus=BUILDING）
     */
    @Transactional
    public KnowledgeDocumentResponse reindex(Long id) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        checkReindexPermission(entity);
        ensureNotBuiltin(entity);
        if (!REVIEW_APPROVED.equals(entity.getReviewStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID, "当前文档未通过审核，无法入库");
        }
        if (!PUBLISH_FAILED.equals(entity.getPublishStatus())
                && !PUBLISH_PUBLISHED.equals(entity.getPublishStatus())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_REVIEW_INVALID,
                    "当前文档正在入库中，无需重复重建");
        }
        entity.setPublishStatus(PUBLISH_BUILDING);
        entity.setPublishError(null);
        entity.setPublishedAt(null);
        documentMapper.updateById(entity);
        // enqueue 内部 UPSERT 会把任务 attempts 重置为 0
        indexTaskService.enqueue(id, entity.getVersion(), vectorSourceOf(entity));
        log.info("知识文档重建索引已入队: id={}, version={}", id, entity.getVersion());
        return toResponse(requireDocument(id), true);
    }

    /**
     * 查询文档索引状态（轻量，供前端轮询"入库中"是否完成）。
     *
     * @param id 文档 ID
     * @return 索引状态
     */
    public KnowledgeIndexStatusResponse indexStatus(Long id) {
        KnowledgeDocumentEntity entity = requireDocument(id);
        if (!queryScope.current().canAccess(entity)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在: " + id);
        }
        KnowledgeIndexTaskEntity task = indexTaskService.findLatest(id);
        return KnowledgeIndexStatusResponse.builder()
                .docId(entity.getId())
                .publishStatus(entity.getPublishStatus())
                .publishError(entity.getPublishError())
                .publishedAt(entity.getPublishedAt())
                .chunkCount(entity.getChunkCount())
                .taskAttempts(task == null ? null : task.getAttempts())
                .taskMaxAttempts(task == null ? null : task.getMaxAttempts())
                .build();
    }

    /**
     * 重建索引权限：作者、知识管理员或审核人。
     *
     * @param entity 文档
     */
    private void checkReindexPermission(KnowledgeDocumentEntity entity) {
        String current = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(current)) {
            // 单元测试 / 系统任务无登录上下文时不做权限校验
            return;
        }
        String currentTenant = SecurityUtils.getCurrentTenantId();
        if (StringUtils.hasText(currentTenant) && !"0".equals(currentTenant)
                && !currentTenant.equals(entity.getTenantId())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND,
                    "知识文档不存在: " + entity.getId());
        }
        if (current.equals(entity.getOwner())
                || SecurityUtils.hasPermission("knowledge:admin")
                || SecurityUtils.hasPermission("knowledge:review")) {
            return;
        }
        throw new BusinessException(ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN,
                "无权操作该知识文档: " + entity.getId());
    }

    /**
     * 文档对应的向量 source：案例文档使用 case: 前缀，其余使用 upload: 前缀。
     *
     * @param entity 文档
     * @return 向量 source
     */
    private String vectorSourceOf(KnowledgeDocumentEntity entity) {
        return "CASE".equals(entity.getLayer())
                ? KnowledgeCaseService.CASE_VECTOR_SOURCE_PREFIX + entity.getId()
                : UPLOAD_VECTOR_SOURCE_PREFIX + entity.getId();
    }

    /**
     * 解析上传文件为纯文本
     * @param file 上传文件
     * @return 解析结果（类型 / 文本 / 文件名）
     */
    public ParsedDocument parse(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID, "文件名缺失");
        }
        String docType = DocumentParserStrategyFactory.resolveDocType(fileName);
        if (docType == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID,
                    "不支持的文件类型，仅支持 md/pdf/txt/docx: " + fileName);
        }
        try {
            DocumentParserStrategy strategy = parserStrategyFactory.getStrategy(docType);
            List<Document> docs = strategy.parse(new InputStreamResource(file.getInputStream()));
            String text = docs.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
            // 上传时即显式校验文本长度，避免审核阶段才发现超长被截断
            vectorIndexer.checkTextLength(text);
            return new ParsedDocument(docType, text, fileName);
        } catch (BusinessException e) {
            // 文本超长等业务校验异常直接透传，不包装为解析失败
            throw e;
        } catch (IOException e) {
            log.error("文档解析失败: {}", fileName, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_UPLOAD_FAILED, "文档解析失败: " + e.getMessage());
        } catch (RuntimeException e) {
            // PDF 等解析器可能在构造/解析阶段抛运行时异常（如无效 PDF）
            log.error("文档解析失败: {}", fileName, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_UPLOAD_FAILED,
                    "文档解析失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * 从块文本提取章节标题（markdown 取首个标题行；其他类型取首个非空行截断）
     * 切分与索引逻辑已收敛到 KnowledgeDocumentIndexer，此处保留包内可见入口供测试与兼容调用。
     */
    String extractSection(String text, String docType) {
        return KnowledgeDocumentIndexer.extractSection(text, docType);
    }

    /**
     * 校验写入的正文：非空且不超过切分上限，避免审核通过后才在异步任务中失败。
     *
     * @param content 正文
     */
    private void validateRawContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID, "文档内容不能为空");
        }
        vectorIndexer.checkTextLength(content);
    }

    /**
     * 删除文档的分块与向量
     * @param entity 文档
     */
    private void deleteChunksAndVectors(KnowledgeDocumentEntity entity) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, entity.getId()));
        deleteVectors(entity);
    }

    /**
     * 按向量 source 删除向量（FAILED/SKIPPED 无向量时为空操作）。
     * 向量删除后内存 BM25 索引仍是旧快照，需在事务提交后触发重建，
     * 否则驳回/删除/改版的内容仍会被关键词检索命中。
     *
     * @param entity 文档
     */
    private void deleteVectors(KnowledgeDocumentEntity entity) {
        int deleted = 0;
        try {
            deleted = jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'source' = ?",
                    vectorSourceOf(entity));
        } catch (Exception e) {
            log.warn("删除文档向量失败（表可能不存在）: docId={}, err={}", entity.getId(), e.getMessage());
        }
        if (deleted > 0) {
            publishIndexRefreshAfterCommit(entity.getId());
        }
    }

    /**
     * 事务提交后发布索引刷新事件；无事务上下文时立即发布。
     *
     * @param docId 文档 ID
     */
    private void publishIndexRefreshAfterCommit(Long docId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new KnowledgeIndexUpdatedEvent(docId));
                }
            });
        } else {
            eventPublisher.publishEvent(new KnowledgeIndexUpdatedEvent(docId));
        }
    }

    /**
     * 查询并校验文档存在
     */
    private KnowledgeDocumentEntity requireDocument(Long id) {
        KnowledgeDocumentEntity entity = documentMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在: " + id);
        }
        return entity;
    }

    /**
     * 实体转响应
     * @param entity 实体
     * @param withChunks 是否包含分块
     * @return 响应
     */
    private KnowledgeDocumentResponse toResponse(KnowledgeDocumentEntity entity, boolean withChunks) {
        KnowledgeDocumentResponse response = KnowledgeDocumentResponse.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .docType(entity.getDocType())
                .layer(entity.getLayer())
                .trustStatus(entity.getTrustStatus())
                .tenantId(entity.getTenantId())
                .deptId(entity.getDeptId())
                .visibility(entity.getVisibility())
                .reviewStatus(entity.getReviewStatus())
                .reviewLevel(entity.getReviewLevel())
                .reviewedBy(entity.getReviewedBy())
                .reviewedAt(entity.getReviewedAt())
                .deptReviewedBy(entity.getDeptReviewedBy())
                .deptReviewedAt(entity.getDeptReviewedAt())
                .reviewNote(entity.getReviewNote())
                .publishStatus(entity.getPublishStatus())
                .publishError(entity.getPublishError())
                .publishedAt(entity.getPublishedAt())
                .version(entity.getVersion())
                .owner(entity.getOwner())
                .serviceIds(fromJson(entity.getServiceIds()))
                .tags(fromJson(entity.getTags()))
                .hitCount(entity.getHitCount())
                .source(entity.getSource())
                .filePath(entity.getFilePath())
                .rawContent(entity.getRawContent())
                .chunkCount(entity.getChunkCount())
                .submittedAt(entity.getSubmittedAt())
                .lastReviewAt(entity.getLastReviewAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
        if (withChunks) {
            List<KnowledgeChunkEntity> chunks = chunkMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeChunkEntity>()
                            .eq(KnowledgeChunkEntity::getDocId, entity.getId())
                            .orderByAsc(KnowledgeChunkEntity::getOrdinal));
            response.setChunks(chunks.stream()
                    .map(c -> KnowledgeChunkResponse.builder()
                            .id(c.getId())
                            .docId(c.getDocId())
                            .tenantId(c.getTenantId())
                            .deptId(c.getDeptId())
                            .ordinal(c.getOrdinal())
                            .content(c.getContent())
                            .section(c.getSection())
                            .vectorId(c.getVectorId())
                            .build())
                    .toList());
        }
        return response;
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
     * JSON 数组字符串转列表
     */
    private List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("列表反序列化失败: {}", json, e);
            return List.of();
        }
    }

    /**
     * 解析结果
     * @param docType 文档类型
     * @param rawContent 原始文本
     * @param fileName 文件名
     */
    public record ParsedDocument(String docType, String rawContent, String fileName) {
    }
}
