package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.exception.GlobalExceptionHandler;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeIndexStatusResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeOnlineCreateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgePublishRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeReviewRequest;
import com.example.opsaiagent.knowledge.service.KnowledgeDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识文档管理接口测试（T18）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识文档接口")
class KnowledgeDocumentControllerTest {

    @Mock
    private KnowledgeDocumentService documentService;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeDocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private KnowledgeDocumentResponse response(Long id, String title, String status) {
        return KnowledgeDocumentResponse.builder()
                .id(id)
                .title(title)
                .docType("MARKDOWN")
                .layer("PRIVATE")
                .trustStatus(status)
                .version(1)
                .serviceIds(List.of("svc-a"))
                .tags(List.of("ops"))
                .chunkCount(3)
                .build();
    }

    // ==================== GET /api/knowledge/documents ====================

    @Test
    @DisplayName("GET 列表：无参数返回分页结果")
    void list_default() throws Exception {
        when(documentService.list(isNull(), isNull(), isNull(), isNull(), eq(1), eq(10)))
                .thenReturn(PageResult.<KnowledgeDocumentResponse>builder()
                        .records(List.of(response(1L, "手册", "VERIFIED")))
                        .total(1).page(1).size(10).pages(1).build());

        mvc.perform(get("/api/knowledge/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("手册"))
                .andExpect(jsonPath("$.data.records[0].trustStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.records[0].serviceIds[0]").value("svc-a"));
    }

    @Test
    @DisplayName("GET 列表：支持全部筛选参数")
    void list_filtered() throws Exception {
        when(documentService.list(eq("手册"), eq("PRIVATE"), eq("PENDING"), eq("svc-a"), eq(2), eq(20)))
                .thenReturn(PageResult.<KnowledgeDocumentResponse>builder()
                        .records(List.of()).total(0).page(2).size(20).pages(0).build());

        mvc.perform(get("/api/knowledge/documents")
                        .param("keyword", "手册")
                        .param("layer", "PRIVATE")
                        .param("trustStatus", "PENDING")
                        .param("service", "svc-a")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2));
        verify(documentService).list(eq("手册"), eq("PRIVATE"), eq("PENDING"), eq("svc-a"), eq(2), eq(20));
    }

    // ==================== GET /api/knowledge/documents/{id} ====================

    @Test
    @DisplayName("GET 详情")
    void detail() throws Exception {
        when(documentService.getById(1L)).thenReturn(response(1L, "手册", "VERIFIED"));

        mvc.perform(get("/api/knowledge/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.chunkCount").value(3));
    }

    // ==================== POST /api/knowledge/documents/upload ====================

    @Test
    @DisplayName("POST 上传：multipart 上传并解析")
    void upload() throws Exception {
        when(documentService.upload(any(), eq("手册"), eq("PRIVATE"), eq("ops"),
                eq(List.of("svc-a")), eq(List.of("ops"))))
                .thenReturn(response(2L, "手册", "PENDING"));

        MockMultipartFile file = new MockMultipartFile("file", "manual.md", "text/markdown",
                "# 标题\n内容".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/knowledge/documents/upload")
                        .file(file)
                        .param("title", "手册")
                        .param("layer", "PRIVATE")
                        .param("owner", "ops")
                        .param("serviceIds", "svc-a")
                        .param("tags", "ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trustStatus").value("PENDING"));
        verify(documentService).upload(any(), eq("手册"), eq("PRIVATE"), eq("ops"),
                eq(List.of("svc-a")), eq(List.of("ops")));
    }

    @Test
    @DisplayName("POST 上传：无附加元数据时传 null")
    void upload_withoutMeta() throws Exception {
        when(documentService.upload(any(), isNull(), eq("PRIVATE"), isNull(), isNull(), isNull()))
                .thenReturn(response(3L, "manual.md", "PENDING"));

        MockMultipartFile file = new MockMultipartFile("file", "manual.md", "text/markdown",
                "内容".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/knowledge/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3));
    }

    // ==================== POST /api/knowledge/documents/{id}/publish ====================

    @Test
    @DisplayName("POST 发布审核：通过")
    void publish_approved() throws Exception {
        when(documentService.publish(1L, true)).thenReturn(response(1L, "手册", "VERIFIED"));

        mvc.perform(post("/api/knowledge/documents/1/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("approved", true, "comment", "ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trustStatus").value("VERIFIED"));
        verify(documentService).publish(1L, true);
    }

    @Test
    @DisplayName("POST 发布审核：驳回")
    void publish_rejected() throws Exception {
        when(documentService.publish(1L, false)).thenReturn(response(1L, "手册", "PENDING"));

        mvc.perform(post("/api/knowledge/documents/1/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("approved", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trustStatus").value("PENDING"));
        verify(documentService).publish(1L, false);
    }

    @Test
    @DisplayName("POST 发布审核：缺少 approved 返回参数错误")
    void publish_missingApproved() throws Exception {
        mvc.perform(post("/api/knowledge/documents/1/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ==================== PUT /api/knowledge/documents/{id} ====================

    @Test
    @DisplayName("PUT 编辑：更新元数据")
    void update() throws Exception {
        when(documentService.update(eq(1L), any(KnowledgeDocumentUpdateRequest.class)))
                .thenReturn(response(1L, "新标题", "VERIFIED"));

        KnowledgeDocumentUpdateRequest req = new KnowledgeDocumentUpdateRequest();
        req.setTitle("新标题");
        req.setOwner("ops");

        mvc.perform(put("/api/knowledge/documents/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新标题"));
    }

    @Test
    @DisplayName("PUT 编辑：空标题返回参数错误")
    void update_blankTitle() throws Exception {
        mvc.perform(put("/api/knowledge/documents/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ==================== DELETE /api/knowledge/documents/{id} ====================

    // ==================== DELETE /api/knowledge/documents/{id} ====================

    @Test
    @DisplayName("DELETE 删除")
    void deleteDocument() throws Exception {
        mvc.perform(delete("/api/knowledge/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(1));
        verify(documentService).delete(1L);
    }

    // ==================== GET /api/knowledge/documents/{id}/index-status ====================

    @Test
    @DisplayName("GET 索引状态：返回 publishStatus 与任务重试信息")
    void indexStatus() throws Exception {
        when(documentService.indexStatus(1L)).thenReturn(KnowledgeIndexStatusResponse.builder()
                .docId(1L)
                .publishStatus("BUILDING")
                .chunkCount(0)
                .taskAttempts(1)
                .taskMaxAttempts(3)
                .build());

        mvc.perform(get("/api/knowledge/documents/1/index-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docId").value(1))
                .andExpect(jsonPath("$.data.publishStatus").value("BUILDING"))
                .andExpect(jsonPath("$.data.taskAttempts").value(1))
                .andExpect(jsonPath("$.data.taskMaxAttempts").value(3));
        verify(documentService).indexStatus(1L);
    }

    @Test
    @DisplayName("GET 索引状态：入库失败时返回 publishError")
    void indexStatus_failed() throws Exception {
        when(documentService.indexStatus(1L)).thenReturn(KnowledgeIndexStatusResponse.builder()
                .docId(1L)
                .publishStatus("FAILED")
                .publishError("embedding 调用失败")
                .build());

        mvc.perform(get("/api/knowledge/documents/1/index-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.publishError").value("embedding 调用失败"));
    }

    // ==================== POST /api/knowledge/documents/{id}/reindex ====================

    @Test
    @DisplayName("POST 重建索引：触发 reindex 并返回文档详情")
    void reindex() throws Exception {
        when(documentService.reindex(1L)).thenReturn(response(1L, "手册", "VERIFIED"));

        mvc.perform(post("/api/knowledge/documents/1/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
        verify(documentService).reindex(1L);
    }

    // ==================== P1-⑤ 在线编写 / 超期扫描 ====================

    @Test
    @DisplayName("POST /api/knowledge/documents/online-create：在线编写返回待审核文档")
    void onlineCreate() throws Exception {
        when(documentService.onlineCreate(any())).thenReturn(KnowledgeDocumentResponse.builder()
                .id(9L).title("Nginx 手册").docType("MARKDOWN").layer("PRIVATE")
                .trustStatus("PENDING").version(1).build());

        mvc.perform(post("/api/knowledge/documents/online-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nginx 手册\",\"content\":\"配置说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Nginx 手册"))
                .andExpect(jsonPath("$.data.trustStatus").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/knowledge/documents/expire-scan：返回过期标记数量")
    void expireScan() throws Exception {
        when(documentService.expireOverdueDocuments()).thenReturn(3);

        mvc.perform(post("/api/knowledge/documents/expire-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expired").value(3));
    }

    @Test
    @DisplayName("鉴权：写接口要求对应权限点")
    void writeEndpoints_requirePermission() throws Exception {
        assertAuthority("upload", "knowledge:write",
                MultipartFile.class, String.class, String.class, String.class, String.class, String.class);
        assertAuthority("publish", "knowledge:review", Long.class, KnowledgePublishRequest.class);
        assertAuthority("submit", "knowledge:write", Long.class);
        assertAuthority("review", "knowledge:review", Long.class, KnowledgeReviewRequest.class);
        assertAuthority("update", "knowledge:write", Long.class, KnowledgeDocumentUpdateRequest.class);
        assertAuthority("delete", "knowledge:write", Long.class);
        assertAuthority("onlineCreate", "knowledge:write", KnowledgeOnlineCreateRequest.class);
        assertAuthority("expireScan", "knowledge:admin");
    }

    @Test
    @DisplayName("鉴权：读接口要求 knowledge:read")
    void readEndpoints_requireReadPermission() throws Exception {
        assertAuthority("list", "knowledge:read",
                String.class, String.class, String.class, String.class, int.class, int.class);
        assertAuthority("detail", "knowledge:read", Long.class);
        assertAuthority("indexStatus", "knowledge:read", Long.class);
    }

    @Test
    @DisplayName("鉴权：重建索引允许写权限或审核权限")
    void reindex_requireWriteOrReviewPermission() throws Exception {
        java.lang.reflect.Method m = KnowledgeDocumentController.class.getMethod("reindex", Long.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).contains("hasAnyAuthority('knowledge:write', 'knowledge:review')");
    }

    private void assertAuthority(String methodName, String authority, Class<?>... params) throws NoSuchMethodException {
        java.lang.reflect.Method m = KnowledgeDocumentController.class.getMethod(methodName, params);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).contains("hasAuthority('" + authority + "')");
    }
}
