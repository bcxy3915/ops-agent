package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.exception.GlobalExceptionHandler;
import com.example.opsaiagent.knowledge.dto.ConflictResolveResponse;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识文档上传质检接口测试（P1）
 * 覆盖：POST /check 质检、POST /conflicts/{id}/resolve 裁定、写接口仅 ADMIN/OPERATOR、审计注解。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识文档上传质检接口")
class KnowledgeQualityControllerTest {

    @Mock
    private KnowledgeQualityService qualityService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeQualityController(qualityService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/knowledge/quality/check：返回冲突/过时/脱敏预览")
    void check() throws Exception {
        when(qualityService.check(any())).thenReturn(QualityCheckResponse.builder()
                .conflicts(List.of(QualityCheckResponse.ConflictCandidate.builder()
                        .matchedDocId(2L).matchedTitle("已有手册").conflictType("TITLE").similarity(1.0).build()))
                .staleDocs(List.of(QualityCheckResponse.StaleDocItem.builder()
                        .id(3L).title("过期文档").reviewAt(LocalDateTime.now()).build()))
                .maskedPreview(QualityCheckResponse.MaskedPreview.builder()
                        .maskedContent("脱敏后")
                        .sensitiveItems(List.of(QualityCheckResponse.SensitiveItem.builder()
                                .type("IP").raw("192.168.1.10").masked("*.*.*.*").count(1).build()))
                        .build())
                .build());

        mvc.perform(post("/api/knowledge/quality/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nginx 手册\",\"content\":\"内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].conflictType").value("TITLE"))
                .andExpect(jsonPath("$.data.staleDocs[0].title").value("过期文档"))
                .andExpect(jsonPath("$.data.maskedPreview.sensitiveItems[0].type").value("IP"));
    }

    @Test
    @DisplayName("POST /api/knowledge/quality/conflicts/{id}/resolve：返回裁定结果")
    void resolve() throws Exception {
        when(qualityService.resolve(eq(100L), any())).thenReturn(ConflictResolveResponse.builder()
                .conflictId(100L).status("RESOLVED").decision("KEEP_NEW")
                .resolvedBy("admin").resolvedAt(LocalDateTime.now()).build());

        mvc.perform(post("/api/knowledge/quality/conflicts/100/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"KEEP_NEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("KEEP_NEW"))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));

        verify(qualityService).resolve(eq(100L), any());
    }

    @Test
    @DisplayName("质检接口为写操作：check 要求 knowledge:write，resolve 要求 knowledge:review")
    void writeEndpoints_requirePermission() throws Exception {
        java.lang.reflect.Method check = KnowledgeQualityController.class.getMethod("check", com.example.opsaiagent.knowledge.dto.QualityCheckRequest.class);
        java.lang.reflect.Method resolve = KnowledgeQualityController.class.getMethod("resolve", Long.class, com.example.opsaiagent.knowledge.dto.ConflictResolveRequest.class);

        PreAuthorize checkAuth = check.getAnnotation(PreAuthorize.class);
        PreAuthorize resolveAuth = resolve.getAnnotation(PreAuthorize.class);
        assertThat(checkAuth).isNotNull();
        assertThat(resolveAuth).isNotNull();
        assertThat(checkAuth.value()).contains("hasAuthority('knowledge:write')");
        assertThat(resolveAuth.value()).contains("hasAuthority('knowledge:review')");
    }

    @Test
    @DisplayName("质检接口带审计注解：check 与 resolve 均记录操作")
    void writeEndpoints_audited() throws Exception {
        java.lang.reflect.Method check = KnowledgeQualityController.class.getMethod("check", com.example.opsaiagent.knowledge.dto.QualityCheckRequest.class);
        java.lang.reflect.Method resolve = KnowledgeQualityController.class.getMethod("resolve", Long.class, com.example.opsaiagent.knowledge.dto.ConflictResolveRequest.class);

        AuditLog checkAudit = check.getAnnotation(AuditLog.class);
        AuditLog resolveAudit = resolve.getAnnotation(AuditLog.class);
        assertThat(checkAudit).isNotNull();
        assertThat(checkAudit.operation()).isEqualTo("UPLOAD_KNOWLEDGE");
        assertThat(resolveAudit).isNotNull();
        assertThat(resolveAudit.operation()).isEqualTo("CONFLICT_RESOLVE");
    }
}
