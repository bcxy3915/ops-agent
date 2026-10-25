package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.exception.GlobalExceptionHandler;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeVersionResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeDocumentService;
import com.example.opsaiagent.knowledge.service.KnowledgeVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识文档版本控制器单元测试（P2-①）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识版本控制器")
class KnowledgeVersionControllerTest {

    @Mock
    private KnowledgeVersionService versionService;
    @Mock
    private KnowledgeDocumentService documentService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeVersionController(versionService, documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private KnowledgeVersionResponse response(Long id, int v) {
        KnowledgeVersionResponse r = new KnowledgeVersionResponse();
        r.setId(id);
        r.setVersion(v);
        r.setContentSnapshot("内容v" + v);
        r.setChangedBy("ops");
        r.setChangeReason("UPDATE");
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    @Test
    @DisplayName("GET /api/knowledge/documents/{docId}/versions：返回版本列表")
    void listVersions() throws Exception {
        when(versionService.listVersions(1L)).thenReturn(List.of(response(2L, 2), response(1L, 1)));

        mvc.perform(get("/api/knowledge/documents/1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[1].contentSnapshot").value("内容v1"));
        verify(versionService).listVersions(1L);
    }

    @Test
    @DisplayName("POST /api/knowledge/documents/{docId}/versions/{versionId}/rollback：执行回滚")
    void rollback() throws Exception {
        KnowledgeDocumentResponse doc = new KnowledgeDocumentResponse();
        doc.setId(1L);
        doc.setTitle("手册");
        doc.setVersion(2);
        when(documentService.rollback(1L, 5L)).thenReturn(doc);

        mvc.perform(post("/api/knowledge/documents/1/versions/5/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        verify(documentService).rollback(1L, 5L);
    }

    @Test
    @DisplayName("鉴权：回滚接口要求 knowledge:write")
    void rollback_requiresWritePermission() throws Exception {
        java.lang.reflect.Method m = KnowledgeVersionController.class.getMethod("rollback", Long.class, Long.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).contains("hasAuthority('knowledge:write')");
    }

    @Test
    @DisplayName("鉴权：版本列表要求 knowledge:read")
    void listVersions_requiresReadPermission() throws Exception {
        java.lang.reflect.Method m = KnowledgeVersionController.class.getMethod("listVersions", Long.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).contains("hasAuthority('knowledge:read')");
    }
}
