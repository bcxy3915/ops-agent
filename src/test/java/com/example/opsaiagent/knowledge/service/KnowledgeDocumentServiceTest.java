package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.dto.PageResult;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentService(documentMapper, chunkMapper, vectorStore, jdbcTemplate, objectMapper);
        lenient().when(documentMapper.selectById(anyLong())).thenReturn(null);
    }

    private KnowledgeDocumentEntity doc(Long id, String title, String status, String layer, String raw) {
        KnowledgeDocumentEntity e = new KnowledgeDocumentEntity();
        e.setId(id);
        e.setTitle(title);
        e.setDocType("MARKDOWN");
        e.setLayer(layer);
        e.setTrustStatus(status);
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
        chunk.setOrdinal(1);
        chunk.setContent("内容块");
        chunk.setSection("S1");
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk));

        KnowledgeDocumentResponse r = service.getById(1L);

        assertThat(r.getId()).isEqualTo(1L);
        assertThat(r.getChunks()).hasSize(1);
        assertThat(r.getChunks().get(0).getSection()).isEqualTo("S1");
    }

    @Test
    @DisplayName("getById：不存在时抛业务异常")
    void getById_notFound() {
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
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
    @DisplayName("publish：审核通过后分块并向量化入库")
    void publish_approved() {
        String longContent = "故障排查指南。" + "详细步骤说明内容。".repeat(200);
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "PENDING", "PRIVATE", longContent));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenReturn(1);

        KnowledgeDocumentResponse r = service.publish(1L, true);

        assertThat(r.getTrustStatus()).isEqualTo("VERIFIED");
        verify(vectorStore).add(anyList());
        ArgumentCaptor<KnowledgeDocumentEntity> updateCaptor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper, org.mockito.Mockito.atLeast(2)).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getAllValues().get(0).getLastReviewAt()).isNotNull();
        assertThat(r.getChunkCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("publish：驳回保持待审核且不向量化")
    void publish_rejected() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "PENDING", "PRIVATE", "内容"));

        KnowledgeDocumentResponse r = service.publish(1L, false);

        assertThat(r.getTrustStatus()).isEqualTo("PENDING");
        assertThat(r.getLastReviewAt()).isNotNull();
        verify(vectorStore, never()).add(anyList());
        verify(chunkMapper, never()).insert(any(KnowledgeChunkEntity.class));
    }

    @Test
    @DisplayName("publish：非待审核文档不能审核通过")
    void publish_notPending() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "VERIFIED", "PRIVATE", "内容"));
        assertThatThrownBy(() -> service.publish(1L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("publish：文档不存在抛业务异常")
    void publish_notFound() {
        assertThatThrownBy(() -> service.publish(99L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    // ==================== update ====================

    @Test
    @DisplayName("update：仅更新元数据不改变版本与状态")
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
        assertThat(r.getOwner()).isEqualTo("new-owner");
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("update：内容变更时重新分块向量化并重置为待审核")
    void update_contentChanged() {
        when(documentMapper.selectById(1L)).thenReturn(doc(1L, "手册", "VERIFIED", "PRIVATE", "旧内容"));
        when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenReturn(1);

        KnowledgeDocumentUpdateRequest req = new KnowledgeDocumentUpdateRequest();
        req.setTitle("手册v2");
        req.setRawContent("全新内容。" + "补充细节说明内容。".repeat(150));

        KnowledgeDocumentResponse r = service.update(1L, req);

        assertThat(r.getVersion()).isEqualTo(2);
        assertThat(r.getTrustStatus()).isEqualTo("PENDING");
        assertThat(r.getRawContent()).startsWith("全新内容");
        verify(chunkMapper).delete(any(LambdaQueryWrapper.class));
        verify(vectorStore).add(anyList());
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
}
