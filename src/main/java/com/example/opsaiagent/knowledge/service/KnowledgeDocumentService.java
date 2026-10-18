package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.dto.PageResult;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.KnowledgeChunkResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeOnlineCreateRequest;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentVersionEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeVersionService versionService;

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
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<>();
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
        entity.setVersion(1);
        entity.setOwner(owner);
        entity.setServiceIds(toJson(serviceIds));
        entity.setTags(toJson(tags));
        entity.setHitCount(0L);
        entity.setSource(parsed.fileName());
        entity.setFilePath("upload:" + parsed.fileName());
        entity.setRawContent(parsed.rawContent());
        entity.setChunkCount(0);
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

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setTitle(title);
        entity.setDocType("MARKDOWN");
        entity.setLayer(LAYER_PRIVATE);
        entity.setTrustStatus(TRUST_PENDING);
        entity.setVersion(1);
        entity.setOwner(request.getOwner());
        entity.setServiceIds(toJson(request.getServiceIds()));
        entity.setTags(toJson(request.getTags()));
        entity.setHitCount(0L);
        entity.setSource(ONLINE_SOURCE_PREFIX + title);
        entity.setFilePath(ONLINE_SOURCE_PREFIX + title);
        entity.setRawContent(request.getContent());
        entity.setChunkCount(0);
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
        List<KnowledgeDocumentEntity> overdue = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getTrustStatus, TRUST_VERIFIED)
                        .isNotNull(KnowledgeDocumentEntity::getReviewAt)
                        .lt(KnowledgeDocumentEntity::getReviewAt, now));
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
        if (approved) {
            if (!TRUST_PENDING.equals(entity.getTrustStatus())) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID,
                        "仅待审核文档可以审核通过，当前状态: " + entity.getTrustStatus());
            }
            entity.setTrustStatus(TRUST_VERIFIED);
            entity.setLastReviewAt(LocalDateTime.now());
            documentMapper.updateById(entity);
            // 分块 + 向量化入库
            chunkAndIndex(entity);
            log.info("知识文档审核通过并入库: id={}, chunks={}", entity.getId(), entity.getChunkCount());
        } else {
            // 驳回：保持待审核状态，仅记录审核时间
            entity.setLastReviewAt(LocalDateTime.now());
            documentMapper.updateById(entity);
            log.info("知识文档审核驳回: id={}", entity.getId());
        }
        return toResponse(requireDocument(id), true);
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
        boolean contentChanged = request.getRawContent() != null
                && !Objects.equals(entity.getRawContent(), request.getRawContent());

        entity.setTitle(request.getTitle().trim());
        if (request.getOwner() != null) {
            entity.setOwner(request.getOwner());
        }
        if (request.getServiceIds() != null) {
            entity.setServiceIds(toJson(request.getServiceIds()));
        }
        if (request.getTags() != null) {
            entity.setTags(toJson(request.getTags()));
        }

        if (contentChanged) {
            entity.setRawContent(request.getRawContent());
            entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
            entity.setTrustStatus(TRUST_PENDING);
            entity.setSubmittedAt(LocalDateTime.now());
            // 清理旧分块与向量后重新入库
            deleteChunksAndVectors(id);
            documentMapper.updateById(entity);
            chunkAndIndex(entity);
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

        // 1. 备份当前内容为历史版本，保留回滚现场
        versionService.recordSnapshot(docId, entity.getRawContent(), currentUsername(),
                KnowledgeVersionService.REASON_ROLLBACK_BACKUP);

        // 2. 用目标版本内容覆盖文档
        entity.setRawContent(target.getContentSnapshot());
        entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
        entity.setTrustStatus(TRUST_PENDING);
        entity.setSubmittedAt(LocalDateTime.now());

        // 3. 清理旧分块与向量，重新入库
        deleteChunksAndVectors(docId);
        documentMapper.updateById(entity);
        chunkAndIndex(entity);
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
        deleteChunksAndVectors(id);
        documentMapper.deleteById(id);
        log.info("知识文档已删除: id={}, title={}", entity.getId(), entity.getTitle());
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
        String lower = fileName.toLowerCase();
        String docType;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            docType = "MARKDOWN";
        } else if (lower.endsWith(".pdf")) {
            docType = "PDF";
        } else if (lower.endsWith(".txt")) {
            docType = "TXT";
        } else if (lower.endsWith(".docx")) {
            docType = "DOCX";
        } else {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID,
                    "不支持的文件类型，仅支持 md/pdf/txt/docx: " + fileName);
        }
        try {
            return new ParsedDocument(docType, extractText(file, docType), fileName);
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
     * 按类型抽取文本
     */
    private String extractText(MultipartFile file, String docType) throws IOException {
        switch (docType) {
            case "MARKDOWN": {
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(false)
                        .withIncludeCodeBlock(true)
                        .withIncludeBlockquote(true)
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(
                        new org.springframework.core.io.ByteArrayResource(file.getBytes()),
                        config);
                return reader.get().stream()
                        .map(Document::getText)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n"));
            }
            case "PDF": {
                PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageBottomMargin(0)
                        .withPagesPerDocument(1)
                        .build();
                PagePdfDocumentReader reader = new PagePdfDocumentReader(
                        new org.springframework.core.io.ByteArrayResource(file.getBytes()),
                        config);
                return reader.get().stream()
                        .map(Document::getText)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n"));
            }
            case "TXT": {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            case "DOCX": {
                return extractDocx(file.getInputStream());
            }
            default:
                throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID, "不支持的文件类型: " + docType);
        }
    }

    /**
     * 抽取 docx 段落文本
     */
    private String extractDocx(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * 将文档全文切块并保存到 knowledge_chunk，同时向量化写入 vector_store
     * @param entity 文档实体（含 raw_content）
     */
    private void chunkAndIndex(KnowledgeDocumentEntity entity) {
        String content = entity.getRawContent();
        if (!StringUtils.hasText(content)) {
            entity.setChunkCount(0);
            documentMapper.updateById(entity);
            return;
        }
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(200)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(List.of(new Document(content)));

        List<KnowledgeChunkEntity> chunkEntities = new ArrayList<>();
        List<Document> vectorDocs = new ArrayList<>();
        int ordinal = 1;
        for (Document chunk : chunks) {
            KnowledgeChunkEntity chunkEntity = new KnowledgeChunkEntity();
            chunkEntity.setDocId(entity.getId());
            chunkEntity.setOrdinal(ordinal);
            chunkEntity.setContent(chunk.getText());
            chunkEntity.setSection(extractSection(chunk.getText(), entity.getDocType()));
            chunkMapper.insert(chunkEntity);
            chunkEntities.add(chunkEntity);

            Document vectorDoc = new Document(chunk.getText(),
                    java.util.Map.of(
                            "source", UPLOAD_VECTOR_SOURCE_PREFIX + entity.getId(),
                            "docId", String.valueOf(entity.getId()),
                            "title", entity.getTitle() == null ? "" : entity.getTitle(),
                            "layer", entity.getLayer(),
                            "trustStatus", entity.getTrustStatus(),
                            "ordinal", String.valueOf(ordinal),
                            "chunkId", String.valueOf(chunkEntity.getId())
                    ));
            vectorDocs.add(vectorDoc);
            ordinal++;
        }
        // 分批向量化（外部 API batch <= 10）
        int batchSize = 10;
        for (int i = 0; i < vectorDocs.size(); i += batchSize) {
            vectorStore.add(vectorDocs.subList(i, Math.min(i + batchSize, vectorDocs.size())));
        }
        entity.setChunkCount(chunkEntities.size());
        documentMapper.updateById(entity);
        log.info("文档分块完成: docId={}, chunks={}", entity.getId(), chunkEntities.size());
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
     * 删除文档的分块与向量
     * @param docId 文档 ID
     */
    private void deleteChunksAndVectors(Long docId) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, docId));
        try {
            String source = UPLOAD_VECTOR_SOURCE_PREFIX + docId;
            String escaped = source.replace("'", "''");
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'source' = ?", escaped);
        } catch (Exception e) {
            log.warn("删除文档向量失败（表可能不存在）: docId={}, err={}", docId, e.getMessage());
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
