package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeIndexStatusResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeOnlineCreateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeReviewRequest;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentVersionEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeIndexTaskEntity;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategyFactory;
import com.example.opsaiagent.knowledge.ingest.parser.DocxDocumentParserStrategy;
import com.example.opsaiagent.knowledge.ingest.parser.MarkdownDocumentParserStrategy;
import com.example.opsaiagent.knowledge.ingest.parser.PdfDocumentParserStrategy;
import com.example.opsaiagent.knowledge.ingest.parser.TxtDocumentParserStrategy;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.rbac.entity.TenantEntity;
import com.example.opsaiagent.rbac.service.DeptService;
import com.example.opsaiagent.rbac.service.TenantService;
import com.example.opsaiagent.security.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识文档服务单元测试（T18）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识文档服务")
class KnowledgeDocumentServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private VectorStore vectorStore;
    private DocumentParserStrategyFactory parserStrategyFactory;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private KnowledgeVersionService versionService;
    @Mock
    private TenantService tenantService;
    @Mock
    private DeptService deptService;
    @Mock
    private KnowledgeIndexTaskService indexTaskService;

    @Spy
    private KnowledgeQueryScope queryScope = new KnowledgeQueryScope(null);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private VectorIndexer vectorIndexer;
    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        vectorIndexer = new VectorIndexer(vectorStore);
        // 审核/发布改为入队异步索引任务，验证任务入队而非同步向量写入

        // 使用真实解析策略工厂，覆盖 md/pdf/txt/docx 真实解析路径
        parserStrategyFactory = new DocumentParserStrategyFactory(java.util.List.of(
                new MarkdownDocumentParserStrategy(), new PdfDocumentParserStrategy(),
                new TxtDocumentParserStrategy(), new DocxDocumentParserStrategy()));
        service = new KnowledgeDocumentService(documentMapper, chunkMapper, vectorIndexer, parserStrategyFactory,
                jdbcTemplate, objectMapper, versionService, tenantService, deptService, queryScope,
                indexTaskService);
        lenient().when(documentMapper.selectById(anyLong())).thenReturn(null);
    }

    private KnowledgeDocumentEntity doc(Long id, String title, String status, String layer, String raw) {
        KnowledgeDocumentEntity e = new KnowledgeDocumentEntity();
        e.setId(id);
        e.setTitle(title);
        e.setDocType("MARKDOWN");
        e.setLayer(layer);
        e.setTrustStatus(status);
        e.setTenantId("t1");
        e.setDeptId(5L);
        e.setVisibility("DEPT");
        e.setReviewStatus("APPROVED");
        e.setReviewLevel("DEPT");
        e.setReviewedBy("admin");
        e.setReviewedAt(LocalDateTime.now());
        e.setReviewNote("通过");
        e.setVersion(1);
        e.setOwner("ops");
        e.setServiceIds("[\"svc-a\"]");
        e.setTags("[\"t1\"]");
        e.setHitCount(0L);
        e.setSource("manual.md");
        e.setFilePath("upload:manual.md");
        e.setRawContent(raw);
        e.setChunkCount(0);
        e.setSubmittedAt(LocalDateTime.now());
        return e;
    }

    private MockMultipartFile mdFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== list ====================

    @Test
    @DisplayName("list：无筛选分页查询")
    void list_noFilter() {
        KnowledgeDocumentEntity e = doc(1L, "手册", "VERIFIED", "BUILTIN", "x");
        when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<KnowledgeDocumentEntity>(1, 10).setRecords(List.of(e)).setTotal(1));

        PageResult<KnowledgeDocumentResponse> r = service.list(null, null, null, null, 1, 10);

        assertThat(r.getTotal()).isEqualTo(1);
        assertThat(r.getRecords()).hasSize(1);
        assertThat(r.getRecords().get(0).getTitle()).isEqualTo("手册");
        assertThat(r.getRecords().get(0).getServiceIds()).containsExactly("svc-a");
        assertThat(r.getRecords().get(0).getTags()).containsExactly("t1");
    }

    @Test
    @DisplayName("list：关键词 / 层级 / 状态 / 服务组合筛选")
    void list_withFilters() {
        when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<KnowledgeDocumentEntity>(1, 10).setRecords(List.of()).setTotal(0));

        PageResult<KnowledgeDocumentResponse> r = service.list("手册", "PRIVATE", "PENDING", "svc-a", 2, 20);

        assertThat(r.getRecords()).isEmpty();
        assertThat(r.getPage()).isEqualTo(2);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectPage(any(Page.class), captor.capture());
        // 分页参数
        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(documentMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("list：页码与每页条数边界收敛")
    void list_bounds() {
        when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<KnowledgeDocumentEntity>(1, 10));

        service.list(null, null, null, null, 0, 0);
        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(documentMapper).selectPage(captor.capture(), any(LambdaQueryWrapper.class));
        assertThat(captor.getValue().getCurrent()).isEqualTo(1);
        assertThat(captor.getValue().getSize()).isEqualTo(1);

        service.list(null, null, null, null, -5, 999);
        verify(documentMapper, org.mockito.Mockito.atLeast(2)).selectPage(captor.capture(), any(LambdaQueryWrapper.class));
        Page last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getCurrent()).isEqualTo(1);
        assertThat(last.getSize()).isEqualTo(100);
    }

    // ==================== getById ====================

    @Test
    @DisplayName("getById：存在时返回详情并附带分块")
    void getById_found() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "VERIFIED", "BUILTIN", "内容"));
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setId(10L);
        chunk.setDocId(1L);
        chunk.setTenantId("t1");
        chunk.setDeptId(5L);
        chunk.setOrdinal(1);
        chunk.setContent("内容块");
        chunk.setSection("S1");
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk));

        KnowledgeDocumentResponse r = service.getById(1L);

        assertThat(r.getId()).isEqualTo(1L);
        assertThat(r.getTenantId()).isEqualTo("t1");
        assertThat(r.getDeptId()).isEqualTo(5L);
        assertThat(r.getVisibility()).isEqualTo("DEPT");
        assertThat(r.getReviewStatus()).isEqualTo("APPROVED");
        assertThat(r.getReviewLevel()).isEqualTo("DEPT");
        assertThat(r.getReviewedBy()).isEqualTo("admin");
        assertThat(r.getReviewNote()).isEqualTo("通过");
        assertThat(r.getChunks()).hasSize(1);
        assertThat(r.getChunks().get(0).getTenantId()).isEqualTo("t1");
        assertThat(r.getChunks().get(0).getDeptId()).isEqualTo(5L);
        assertThat(r.getChunks().get(0).getSection()).isEqualTo("S1");
    }

    @Test
    @DisplayName("getById：不存在时抛业务异常")
    void getById_notFound() {
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("getById：跨租户文档不可见（越权读取拦截）")
    void getById_crossTenantForbidden() {
        KnowledgeQueryScope.Scope scope = queryScope.user("t1", Set.of(5L), "alice");
        when(queryScope.current()).thenReturn(scope);

        KnowledgeDocumentEntity other = doc(9L, "他人私密", "PENDING", "PRIVATE", "内容");
        other.setTenantId("t2");
        other.setOwner("bob");
        other.setVisibility("PERSONAL");
        when(documentMapper.selectById(9L)).thenReturn(other);

        assertThatThrownBy(() -> service.getById(9L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("getById：本人 PERSONAL 文档可见")
    void getById_ownPersonalVisible() {
        KnowledgeQueryScope.Scope scope = queryScope.user("t1", Set.of(5L), "alice");
        when(queryScope.current()).thenReturn(scope);

        KnowledgeDocumentEntity mine = doc(9L, "我的草稿", "PENDING", "PRIVATE", "内容");
        mine.setTenantId("t1");
        mine.setOwner("alice");
        mine.setVisibility("PERSONAL");
        when(documentMapper.selectById(9L)).thenReturn(mine);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        KnowledgeDocumentResponse r = service.getById(9L);

        assertThat(r.getId()).isEqualTo(9L);
        assertThat(r.getOwner()).isEqualTo("alice");
    }

    @Test
    @DisplayName("upload：超过大小限制拒绝")
    void upload_oversizedRejected() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(21L * 1024 * 1024);

        assertThatThrownBy(() -> service.upload(file, "手册", "PRIVATE", null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("update：禁止修改 owner（转赠文档）")
    void update_keepsOwner() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "VERIFIED", "PRIVATE", "内容");
        entity.setOwner("alice");
        when(documentMapper.selectById(1L)).thenReturn(entity);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setTitle("手册");
        request.setOwner("bob");

        service.update(1L, request);

        ArgumentCaptor<KnowledgeDocumentEntity> captor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper).updateById(captor.capture());
        assertThat(captor.getValue().getOwner()).isEqualTo("alice");
    }

    // ==================== upload ====================

    @Test
    @DisplayName("upload：md 文档上传成功并保存为待审核")
    void upload_markdown() {
        when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        KnowledgeDocumentResponse r = service.upload(mdFile("ops-manual.md", "# 标题\n\n正文内容"),
                "运维手册", "PRIVATE", "ops", List.of("svc-a"), List.of("ops"));

        assertThat(r.getTitle()).isEqualTo("运维手册");
        assertThat(r.getDocType()).isEqualTo("MARKDOWN");
        assertThat(r.getLayer()).isEqualTo("PRIVATE");
        assertThat(r.getTrustStatus()).isEqualTo("PENDING");
        assertThat(r.getVersion()).isEqualTo(1);
        assertThat(r.getRawContent()).contains("正文内容");
        assertThat(r.getChunkCount()).isZero();
        ArgumentCaptor<KnowledgeDocumentEntity> captor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper).insert(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo("ops-manual.md");
        assertThat(captor.getValue().getSubmittedAt()).isNotNull();
    }

    @Test
    @DisplayName("upload：无标题时使用文件名")
    void upload_defaultTitleFromFileName() {
        when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        KnowledgeDocumentResponse r = service.upload(mdFile("no-title.md", "内容"), null, "PRIVATE", null, null, null);
        assertThat(r.getTitle()).isEqualTo("no-title.md");
    }

    @Test
    @DisplayName("upload：txt 文档上传成功")
    void upload_txt() {
        when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        MockMultipartFile f = new MockMultipartFile("file", "note.txt", "text/plain",
                "纯文本内容".getBytes(StandardCharsets.UTF_8));
        KnowledgeDocumentResponse r = service.upload(f, "笔记", "PRIVATE", "ops", null, null);
        assertThat(r.getDocType()).isEqualTo("TXT");
        assertThat(r.getRawContent()).isEqualTo("纯文本内容");
    }

    @Test
    @DisplayName("upload：docx 文档上传成功")
    void upload_docx() throws Exception {
        when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        byte[] docxBytes;
        try (XWPFDocument xdoc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            xdoc.createParagraph().createRun().setText("Word 文档第一段");
            xdoc.write(out);
            docxBytes = out.toByteArray();
        }
        MockMultipartFile f = new MockMultipartFile("file", "guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxBytes);
        KnowledgeDocumentResponse r = service.upload(f, "指南", "PRIVATE", "ops", null, null);
        assertThat(r.getDocType()).isEqualTo("DOCX");
        assertThat(r.getRawContent()).contains("Word 文档第一段");
    }

    @Test
    @DisplayName("upload：空文件抛参数异常")
    void upload_emptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.md", "text/markdown", new byte[0]);
        assertThatThrownBy(() -> service.upload(empty, "t", "PRIVATE", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("upload：层级不是 PRIVATE 时拒绝")
    void upload_nonPrivateLayer() {
        assertThatThrownBy(() -> service.upload(mdFile("a.md", "内容"), "t", "BUILTIN", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("upload：不支持的文件类型拒绝")
    void upload_unsupportedType() {
        MockMultipartFile exe = new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[]{1});
        assertThatThrownBy(() -> service.upload(exe, "t", "PRIVATE", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("upload：内容为空拒绝")
    void upload_emptyContent() {
        assertThatThrownBy(() -> service.upload(mdFile("empty.md", ""), "t", "PRIVATE", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("upload：文件名缺失拒绝")
    void upload_missingFileName() {
        MockMultipartFile f = new MockMultipartFile("file", "", "text/markdown", "x".getBytes());
        assertThatThrownBy(() -> service.upload(f, "t", "PRIVATE", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    // ==================== publish ====================

    @Test
    @DisplayName("publish：审核通过后入队索引任务（分块与向量化由后台完成）")
    void publish_approved() {
        String longContent = "故障排查指南。" + "详细步骤说明内容。".repeat(200);
        KnowledgeDocumentEntity entity = doc(1L, "手册", "PENDING", "PRIVATE", longContent);
        entity.setReviewStatus("PENDING_TENANT");
        entity.setReviewLevel("TENANT");
        entity.setReviewedBy("dept-reviewer");
        when(documentMapper.selectById(1L)).thenReturn(entity);
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        lenient().when(chunkMapper.insertBatch(anyList())).thenReturn(1);
        when(tenantService.findById("t1")).thenReturn(Optional.of(tenant("DEPT_AND_TENANT", false)));

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("reviewer");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            KnowledgeDocumentResponse r = service.publish(1L, true);

            assertThat(r.getTrustStatus()).isEqualTo("VERIFIED");
            assertThat(r.getReviewStatus()).isEqualTo("APPROVED");
            assertThat(r.getPublishStatus()).isEqualTo(KnowledgeDocumentService.PUBLISH_BUILDING);
            // 审核不再预删旧索引，新索引成功后整体覆盖
            verify(chunkMapper, never()).delete(any(LambdaQueryWrapper.class));
            verify(indexTaskService).enqueue(anyLong(), anyInt(), anyString());
            ArgumentCaptor<KnowledgeDocumentEntity> updateCaptor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
            verify(documentMapper, org.mockito.Mockito.atLeast(1)).updateById(updateCaptor.capture());
            assertThat(updateCaptor.getAllValues().get(0).getLastReviewAt()).isNotNull();
            // 索引异步执行，审核返回时分块尚未生成
            assertThat(r.getChunkCount()).isZero();
        }
    }

    @Test
    @DisplayName("publish：驳回保持待审核且不向量化")
    void publish_rejected() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "PENDING", "PRIVATE", "内容");
        entity.setReviewStatus("PENDING_TENANT");
        entity.setReviewLevel("TENANT");
        entity.setReviewedBy("dept-reviewer");
        when(documentMapper.selectById(1L)).thenReturn(entity);
        when(tenantService.findById("t1")).thenReturn(Optional.of(tenant("DEPT_AND_TENANT", false)));

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("reviewer");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            KnowledgeDocumentResponse r = service.publish(1L, false);

            assertThat(r.getTrustStatus()).isEqualTo("PENDING");
            assertThat(r.getLastReviewAt()).isNotNull();
            assertThat(r.getPublishStatus()).isEqualTo(KnowledgeDocumentService.PUBLISH_UNPUBLISHED);
            verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
            verify(chunkMapper, never()).insert(any(KnowledgeChunkEntity.class));
        }
    }

    @Test
    @DisplayName("publish：非待审核文档不能审核通过")
    void publish_notPending() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "VERIFIED", "PRIVATE", "内容"));
        assertThatThrownBy(() -> service.publish(1L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_REVIEW_INVALID);
    }

    @Test
    @DisplayName("publish：文档不存在抛业务异常")
    void publish_notFound() {
        assertThatThrownBy(() -> service.publish(99L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    // ==================== reindex / index-status ====================

    @Test
    @DisplayName("reindex：入库失败文档可重试，重置为入库中并重新入队")
    void reindex_failedRetry() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "VERIFIED", "PRIVATE", "内容");
        entity.setPublishStatus(KnowledgeDocumentService.PUBLISH_FAILED);
        entity.setPublishError("embedding 调用超时");
        when(documentMapper.selectById(1L)).thenReturn(entity);
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        KnowledgeDocumentResponse r = service.reindex(1L);

        assertThat(r.getPublishStatus()).isEqualTo(KnowledgeDocumentService.PUBLISH_BUILDING);
        assertThat(r.getPublishError()).isNull();
        assertThat(r.getPublishedAt()).isNull();
        verify(indexTaskService).enqueue(eq(1L), eq(1), eq("upload:1"));
    }

    @Test
    @DisplayName("reindex：已发布文档可重建")
    void reindex_publishedAllowed() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "VERIFIED", "PRIVATE", "内容");
        entity.setPublishStatus(KnowledgeDocumentService.PUBLISH_PUBLISHED);
        when(documentMapper.selectById(1L)).thenReturn(entity);
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        KnowledgeDocumentResponse r = service.reindex(1L);

        assertThat(r.getPublishStatus()).isEqualTo(KnowledgeDocumentService.PUBLISH_BUILDING);
        verify(indexTaskService).enqueue(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("reindex：未通过审核拒绝")
    void reindex_notApproved() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "PENDING", "PRIVATE", "内容");
        entity.setReviewStatus("DRAFT");
        entity.setPublishStatus(KnowledgeDocumentService.PUBLISH_UNPUBLISHED);
        when(documentMapper.selectById(1L)).thenReturn(entity);

        assertThatThrownBy(() -> service.reindex(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_REVIEW_INVALID);
        verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("reindex：入库中重复调用拒绝，避免重复任务")
    void reindex_buildingRejected() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "VERIFIED", "PRIVATE", "内容");
        entity.setPublishStatus(KnowledgeDocumentService.PUBLISH_BUILDING);
        when(documentMapper.selectById(1L)).thenReturn(entity);

        assertThatThrownBy(() -> service.reindex(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_REVIEW_INVALID);
        verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("indexStatus：返回发布状态与任务重试次数")
    void indexStatus_withTask() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "VERIFIED", "PRIVATE", "内容");
        entity.setPublishStatus(KnowledgeDocumentService.PUBLISH_BUILDING);
        entity.setChunkCount(0);
        when(documentMapper.selectById(1L)).thenReturn(entity);
        KnowledgeIndexTaskEntity task = new KnowledgeIndexTaskEntity();
        task.setDocId(1L);
        task.setAttempts(1);
        task.setMaxAttempts(3);
        when(indexTaskService.findLatest(1L)).thenReturn(task);

        KnowledgeIndexStatusResponse r = service.indexStatus(1L);

        assertThat(r.getDocId()).isEqualTo(1L);
        assertThat(r.getPublishStatus()).isEqualTo(KnowledgeDocumentService.PUBLISH_BUILDING);
        assertThat(r.getChunkCount()).isZero();
        assertThat(r.getTaskAttempts()).isEqualTo(1);
        assertThat(r.getTaskMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("indexStatus：无任务时任务字段为空")
    void indexStatus_withoutTask() {
        KnowledgeDocumentEntity entity = doc(1L, "手册", "VERIFIED", "PRIVATE", "内容");
        entity.setPublishStatus(KnowledgeDocumentService.PUBLISH_PUBLISHED);
        when(documentMapper.selectById(1L)).thenReturn(entity);
        when(indexTaskService.findLatest(1L)).thenReturn(null);

        KnowledgeIndexStatusResponse r = service.indexStatus(1L);

        assertThat(r.getPublishStatus()).isEqualTo(KnowledgeDocumentService.PUBLISH_PUBLISHED);
        assertThat(r.getTaskAttempts()).isNull();
        assertThat(r.getTaskMaxAttempts()).isNull();
    }

    // ==================== update ====================

    @Test
    @DisplayName("update：仅更新元数据不改变版本与状态（owner 不允许被转赠）")
    void update_metadataOnly() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "旧标题", "VERIFIED", "PRIVATE", "原内容"));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        KnowledgeDocumentUpdateRequest req = new KnowledgeDocumentUpdateRequest();
        req.setTitle("新标题");
        req.setOwner("new-owner");
        req.setServiceIds(List.of("svc-b"));
        req.setTags(List.of("t2"));

        KnowledgeDocumentResponse r = service.update(1L, req);

        assertThat(r.getTitle()).isEqualTo("新标题");
        assertThat(r.getVersion()).isEqualTo(1);
        assertThat(r.getTrustStatus()).isEqualTo("VERIFIED");
        assertThat(r.getOwner()).isEqualTo("ops");
        verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("update：内容变更后下线旧向量并重置为草稿")
    void update_contentChanged() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "VERIFIED", "PRIVATE", "旧内容"));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        KnowledgeDocumentUpdateRequest req = new KnowledgeDocumentUpdateRequest();
        req.setTitle("手册v2");
        req.setRawContent("全新内容。" + "补充细节说明内容。".repeat(150));

        KnowledgeDocumentResponse r = service.update(1L, req);

        assertThat(r.getVersion()).isEqualTo(2);
        assertThat(r.getTrustStatus()).isEqualTo("PENDING");
        assertThat(r.getReviewStatus()).isEqualTo("DRAFT");
        assertThat(r.getReviewLevel()).isEqualTo("NONE");
        assertThat(r.getVisibility()).isEqualTo("PERSONAL");
        assertThat(r.getPublishStatus()).isEqualTo(KnowledgeDocumentService.PUBLISH_UNPUBLISHED);
        assertThat(r.getRawContent()).startsWith("全新内容");
        verify(chunkMapper).delete(any(LambdaQueryWrapper.class));
        verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
        verify(jdbcTemplate).update(eq("DELETE FROM vector_store WHERE metadata->>'source' = ?"), any(String.class));
    }

    @Test
    @DisplayName("update：文档不存在抛业务异常")
    void update_notFound() {
        KnowledgeDocumentUpdateRequest req = new KnowledgeDocumentUpdateRequest();
        req.setTitle("t");
        assertThatThrownBy(() -> service.update(99L, req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete：删除文档、分块与向量")
    void delete_ok() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "VERIFIED", "PRIVATE", "内容"));

        service.delete(1L);

        verify(chunkMapper).delete(any(LambdaQueryWrapper.class));
        verify(jdbcTemplate).update(eq("DELETE FROM vector_store WHERE metadata->>'source' = ?"), any(String.class));
        verify(documentMapper).deleteById(1L);
    }

    @Test
    @DisplayName("delete：文档不存在抛业务异常")
    void delete_notFound() {
        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    // ==================== parse ====================

    @Test
    @DisplayName("parse：识别 markdown / txt 类型并抽取文本")
    void parse_mdAndTxt() {
        MultipartFile md = mdFile("a.md", "# 标题\n\n正文内容");
        KnowledgeDocumentService.ParsedDocument pm = service.parse(md);
        assertThat(pm.docType()).isEqualTo("MARKDOWN");
        assertThat(pm.rawContent()).contains("正文内容");
        assertThat(pm.fileName()).isEqualTo("a.md");

        MultipartFile txt = new MockMultipartFile("file", "b.txt", "text/plain", "文本".getBytes(StandardCharsets.UTF_8));
        KnowledgeDocumentService.ParsedDocument pt = service.parse(txt);
        assertThat(pt.docType()).isEqualTo("TXT");
        assertThat(pt.rawContent()).isEqualTo("文本");
    }

    @Test
    @DisplayName("parse：pdf 解析失败时抛上传失败异常")
    void parse_pdfInvalid() {
        MockMultipartFile pdf = new MockMultipartFile("file", "bad.pdf", "application/pdf",
                "%PDF-1.4 not a real pdf".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.parse(pdf))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("parse：不支持类型抛参数异常")
    void parse_unsupported() {
        MockMultipartFile f = new MockMultipartFile("file", "a.xyz", "text/plain", "x".getBytes());
        assertThatThrownBy(() -> service.parse(f))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    // ==================== extractSection ====================

    @Test
    @DisplayName("extractSection：markdown 提取标题行")
    void extractSection_markdown() {
        assertThat(service.extractSection("## 故障处理\n内容", "MARKDOWN")).isEqualTo("故障处理");
        assertThat(service.extractSection("无标题段落内容", "MARKDOWN")).isEqualTo("无标题段落内容");
    }

    @Test
    @DisplayName("extractSection：非 markdown 取首个非空行并截断")
    void extractSection_plain() {
        assertThat(service.extractSection("第一行\n第二行", "TXT")).isEqualTo("第一行");
        String longLine = "x".repeat(200);
        assertThat(service.extractSection(longLine, "TXT")).hasSize(100);
        assertThat(service.extractSection("", "TXT")).isNull();
        assertThat(service.extractSection("  ", "TXT")).isNull();
        assertThat(service.extractSection(null, "TXT")).isNull();
    }

    // ==================== P1-⑤ 在线编写 / 超期判定 ====================

    @Test
    @DisplayName("onlineCreate：创建待审核文档，source 为 online 前缀，默认元数据正确")
    void onlineCreate_success() {
        KnowledgeOnlineCreateRequest req = new KnowledgeOnlineCreateRequest();
        req.setTitle("Nginx 部署手册");
        req.setContent("# Nginx\n配置说明");
        req.setOwner("ops");
        req.setServiceIds(List.of("todo-service"));
        req.setTags(List.of("nginx"));

        service.onlineCreate(req);

        ArgumentCaptor<KnowledgeDocumentEntity> captor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper).insert(captor.capture());
        KnowledgeDocumentEntity saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("Nginx 部署手册");
        assertThat(saved.getDocType()).isEqualTo("MARKDOWN");
        assertThat(saved.getLayer()).isEqualTo("PRIVATE");
        assertThat(saved.getTrustStatus()).isEqualTo("PENDING");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getHitCount()).isEqualTo(0L);
        assertThat(saved.getChunkCount()).isZero();
        assertThat(saved.getSource()).startsWith("online:");
        assertThat(saved.getFilePath()).startsWith("online:");
        assertThat(saved.getEffectiveAt()).isNotNull();
    }

    @Test
    @DisplayName("onlineCreate：标题或内容为空抛参数异常")
    void onlineCreate_blankRejected() {
        KnowledgeOnlineCreateRequest emptyTitle = new KnowledgeOnlineCreateRequest();
        emptyTitle.setContent("内容");
        assertThatThrownBy(() -> service.onlineCreate(emptyTitle))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);

        KnowledgeOnlineCreateRequest emptyContent = new KnowledgeOnlineCreateRequest();
        emptyContent.setTitle("标题");
        assertThatThrownBy(() -> service.onlineCreate(emptyContent))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service.onlineCreate(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("expireOverdueDocuments：复核到期的 VERIFIED 文档标记为 EXPIRED")
    void expireOverdueDocuments_marksOverdue() {
        KnowledgeDocumentEntity overdue = doc(1L, "旧文档", "VERIFIED", "PRIVATE", "内容");
        overdue.setReviewAt(LocalDateTime.now().minusDays(3));
        KnowledgeDocumentEntity ok = doc(2L, "正常文档", "VERIFIED", "PRIVATE", "内容");
        ok.setReviewAt(LocalDateTime.now().plusDays(3));
        when(documentMapper.selectList(any())).thenReturn(List.of(overdue, ok));

        int count = service.expireOverdueDocuments();

        assertThat(count).isEqualTo(2);
        ArgumentCaptor<KnowledgeDocumentEntity> captor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues()).allMatch(d -> "EXPIRED".equals(d.getTrustStatus()));
    }

    @Test
    @DisplayName("expireOverdueDocuments：无到期文档返回 0")
    void expireOverdueDocuments_none() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.expireOverdueDocuments()).isZero();
    }

    // ==================== P2-① 版本回滚 ====================

    @Test
    @DisplayName("rollback：回滚到历史版本并重置为草稿")
    void rollback_success() {
        KnowledgeDocumentVersionEntity target = new KnowledgeDocumentVersionEntity();
        target.setId(5L);
        target.setDocId(1L);
        target.setVersion(1);
        target.setContentSnapshot("历史版本内容");
        when(versionService.requireVersion(1L, 5L)).thenReturn(target);
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "VERIFIED", "PRIVATE", "当前内容"));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        KnowledgeDocumentResponse r = service.rollback(1L, 5L);

        assertThat(r.getRawContent()).isEqualTo("历史版本内容");
        assertThat(r.getVersion()).isEqualTo(2);
        assertThat(r.getTrustStatus()).isEqualTo("PENDING");
        assertThat(r.getReviewStatus()).isEqualTo("DRAFT");
        // 回滚前先备份当前内容为历史快照
        verify(versionService).recordSnapshot(eq(1L), eq("当前内容"), any(), eq("ROLLBACK_BACKUP"));
        verify(chunkMapper).delete(any(LambdaQueryWrapper.class));
        verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
        verify(jdbcTemplate).update(eq("DELETE FROM vector_store WHERE metadata->>'source' = ?"), any(String.class));
    }

    @Test
    @DisplayName("rollback：目标版本不存在抛业务异常")
    void rollback_versionNotFound() {
        when(versionService.requireVersion(1L, 999L))
                .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND, "知识文档版本不存在"));

        assertThatThrownBy(() -> service.rollback(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND);
        verify(documentMapper, never()).updateById(any(KnowledgeDocumentEntity.class));
    }

    @Test
    @DisplayName("rollback：文档不存在抛业务异常")
    void rollback_documentNotFound() {
        KnowledgeDocumentVersionEntity target = new KnowledgeDocumentVersionEntity();
        target.setId(5L);
        target.setDocId(99L);
        target.setVersion(1);
        when(versionService.requireVersion(99L, 5L)).thenReturn(target);

        assertThatThrownBy(() -> service.rollback(99L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    // ==================== P3-B10 审核流 ====================

    @Test
    @DisplayName("submit：DEPT_ONLY 模式进入待部门审核")
    void submit_deptOnly() {
        KnowledgeDocumentEntity doc = doc(1L, "手册", "PENDING", "PRIVATE", "内容");
        doc.setReviewStatus("DRAFT");
        doc.setReviewLevel("NONE");
        doc.setVisibility("PERSONAL");
        doc.setOwner("ops");
        doc.setTenantId("t1");
        doc.setDeptId(5L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(tenantService.findById("t1"))
                .thenReturn(Optional.of(tenant("DEPT_ONLY", false)));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("ops");

            KnowledgeDocumentResponse r = service.submitForReview(1L);

            assertThat(r.getReviewStatus()).isEqualTo("PENDING_DEPT");
            assertThat(r.getReviewLevel()).isEqualTo("DEPT");
            assertThat(r.getVisibility()).isEqualTo("DEPT");
            verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
        }
    }

    @Test
    @DisplayName("submit：NONE 模式自动通过并入库")
    void submit_noneAutoApprove() {
        KnowledgeDocumentEntity doc = doc(1L, "手册", "PENDING", "PRIVATE", "内容");
        doc.setReviewStatus("DRAFT");
        doc.setReviewLevel("NONE");
        doc.setVisibility("PERSONAL");
        doc.setOwner("ops");
        doc.setTenantId("t1");
        doc.setDeptId(5L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(tenantService.findById("t1"))
                .thenReturn(Optional.of(tenant("NONE", false)));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        lenient().when(chunkMapper.insertBatch(anyList())).thenReturn(1);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("ops");

            KnowledgeDocumentResponse r = service.submitForReview(1L);

            assertThat(r.getReviewStatus()).isEqualTo("APPROVED");
            assertThat(r.getReviewLevel()).isEqualTo("TENANT");
            assertThat(r.getTrustStatus()).isEqualTo("VERIFIED");
            verify(indexTaskService).enqueue(anyLong(), anyInt(), anyString());
        }
    }

    @Test
    @DisplayName("review：部门审核通过（DEPT_ONLY）并入库")
    void review_deptApprove_deptOnly() {
        KnowledgeDocumentEntity doc = reviewDoc();
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(tenantService.findById("t1"))
                .thenReturn(Optional.of(tenant("DEPT_ONLY", false)));
        when(deptService.subtreeIds("t1", 5L)).thenReturn(List.of(5L));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        lenient().when(chunkMapper.insertBatch(anyList())).thenReturn(1);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("reviewer");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(SecurityUtils::getCurrentDeptId).thenReturn(5L);
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            KnowledgeDocumentResponse r = service.review(1L, reviewRequest("DEPT", true));

            assertThat(r.getReviewStatus()).isEqualTo("APPROVED");
            assertThat(r.getReviewLevel()).isEqualTo("DEPT");
            assertThat(r.getVisibility()).isEqualTo("DEPT");
            assertThat(r.getTrustStatus()).isEqualTo("VERIFIED");
            verify(indexTaskService).enqueue(anyLong(), anyInt(), anyString());
        }
    }

    @Test
    @DisplayName("review：部门审核通过后进入企业审核（DEPT_AND_TENANT）")
    void review_deptApprove_twoLevel() {
        KnowledgeDocumentEntity doc = reviewDoc();
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(tenantService.findById("t1"))
                .thenReturn(Optional.of(tenant("DEPT_AND_TENANT", false)));
        when(deptService.subtreeIds("t1", 5L)).thenReturn(List.of(5L));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("reviewer");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(SecurityUtils::getCurrentDeptId).thenReturn(5L);
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            KnowledgeDocumentResponse r = service.review(1L, reviewRequest("DEPT", true));

            assertThat(r.getReviewStatus()).isEqualTo("PENDING_TENANT");
            assertThat(r.getReviewLevel()).isEqualTo("TENANT");
            verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
        }
    }

    @Test
    @DisplayName("review：企业审核通过并提升为企业可见")
    void review_tenantApprove() {
        KnowledgeDocumentEntity doc = reviewDoc();
        doc.setReviewStatus("PENDING_TENANT");
        doc.setReviewLevel("TENANT");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(tenantService.findById("t1"))
                .thenReturn(Optional.of(tenant("DEPT_AND_TENANT", false)));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        lenient().when(chunkMapper.insertBatch(anyList())).thenReturn(1);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("reviewer");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            KnowledgeDocumentResponse r = service.review(1L, reviewRequest("TENANT", true));

            assertThat(r.getReviewStatus()).isEqualTo("APPROVED");
            assertThat(r.getReviewLevel()).isEqualTo("TENANT");
            assertThat(r.getVisibility()).isEqualTo("TENANT");
            verify(indexTaskService).enqueue(anyLong(), anyInt(), anyString());
        }
    }

    @Test
    @DisplayName("review：部门管理员不能执行企业审核")
    void review_tenantRequiresTenantAdmin() {
        KnowledgeDocumentEntity doc = reviewDoc();
        doc.setReviewStatus("PENDING_TENANT");
        doc.setReviewLevel("TENANT");
        doc.setReviewedBy("dept-reviewer");
        when(documentMapper.selectById(1L)).thenReturn(doc);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("dept-admin");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);
            sec.when(() -> SecurityUtils.hasPermission(anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.review(1L, reviewRequest("TENANT", true)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅企业管理员可执行企业审核");
        }
    }

    @Test
    @DisplayName("review：部门审核人与企业审核人不能为同一人")
    void review_tenantRequiresDifferentReviewer() {
        KnowledgeDocumentEntity doc = reviewDoc();
        doc.setReviewStatus("PENDING_TENANT");
        doc.setReviewLevel("TENANT");
        doc.setReviewedBy("reviewer");
        when(documentMapper.selectById(1L)).thenReturn(doc);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("reviewer");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.review(1L, reviewRequest("TENANT", true)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("部门审核人与企业审核人不能为同一人");
        }
    }

    @Test
    @DisplayName("review：驳回退回个人可见且不入库")
    void review_reject() {
        KnowledgeDocumentEntity doc = reviewDoc();
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(tenantService.findById("t1"))
                .thenReturn(Optional.of(tenant("DEPT_ONLY", false)));
        when(deptService.subtreeIds("t1", 5L)).thenReturn(List.of(5L));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("reviewer");
            sec.when(SecurityUtils::getCurrentTenantId).thenReturn("t1");
            sec.when(SecurityUtils::getCurrentDeptId).thenReturn(5L);
            sec.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            KnowledgeDocumentResponse r = service.review(1L, reviewRequest("DEPT", false));

            assertThat(r.getReviewStatus()).isEqualTo("REJECTED");
            assertThat(r.getReviewLevel()).isEqualTo("NONE");
            assertThat(r.getVisibility()).isEqualTo("PERSONAL");
            verify(indexTaskService, never()).enqueue(anyLong(), anyInt(), anyString());
        }
    }

    @Test
    @DisplayName("submit：非作者不能提交")
    void submit_wrongOwner() {
        KnowledgeDocumentEntity doc = doc(1L, "手册", "PENDING", "PRIVATE", "内容");
        doc.setReviewStatus("DRAFT");
        doc.setOwner("ops");
        when(documentMapper.selectById(1L)).thenReturn(doc);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("other");

            assertThatThrownBy(() -> service.submitForReview(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_OPERATION_FORBIDDEN);
        }
    }

    private KnowledgeDocumentEntity reviewDoc() {
        KnowledgeDocumentEntity doc = doc(1L, "手册", "PENDING", "PRIVATE", "内容");
        doc.setReviewStatus("PENDING_DEPT");
        doc.setReviewLevel("DEPT");
        doc.setVisibility("DEPT");
        doc.setOwner("ops");
        doc.setTenantId("t1");
        doc.setDeptId(5L);
        return doc;
    }

    private TenantEntity tenant(String mode, boolean fourEyes) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("t1");
        tenant.setKnowledgeReviewMode(mode);
        tenant.setReviewFourEyes(fourEyes);
        return tenant;
    }

    private KnowledgeReviewRequest reviewRequest(String level, boolean approved) {
        KnowledgeReviewRequest request = new KnowledgeReviewRequest();
        request.setLevel(level);
        request.setApproved(approved);
        request.setComment(approved ? "通过" : "内容不完善");
        return request;
    }
}
