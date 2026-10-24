package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.exception.GlobalExceptionHandler;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseReferenceResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHitLogResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识命中流水控制器单元测试（P2）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识命中流水控制器")
class KnowledgeHitLogControllerTest {

    @Mock
    private KnowledgeHitLogService hitLogService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeHitLogController(hitLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private KnowledgeHitLogResponse log(Long id, Long docId, String source) {
        KnowledgeHitLogResponse r = new KnowledgeHitLogResponse();
        r.setId(id);
        r.setDocId(docId);
        r.setQuery("q");
        r.setHitSource(source);
        r.setChannel("admin");
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    @Test
    @DisplayName("GET /api/knowledge/documents/{docId}/hits：返回命中流水分页")
    void listDocumentHits() throws Exception {
        PageResult<KnowledgeHitLogResponse> page = PageResult.<KnowledgeHitLogResponse>builder()
                .total(2)
                .records(List.of(log(1L, 9L, "ASK"), log(2L, 9L, "TEST")))
                .build();
        when(hitLogService.listByDocument(9L, 1, 10)).thenReturn(page);

        mvc.perform(get("/api/knowledge/documents/9/hits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[1].hitSource").value("TEST"));
        verify(hitLogService).listByDocument(9L, 1, 10);
    }

    @Test
    @DisplayName("GET /api/knowledge/cases/{caseId}/references：返回案例引用统计")
    void listCaseReferences() throws Exception {
        KnowledgeCaseReferenceResponse ref = new KnowledgeCaseReferenceResponse();
        ref.setCaseId(5L);
        ref.setDocId(20L);
        ref.setTotalHits(3L);
        ref.setRecentHits(List.of(log(1L, 20L, "ASK")));
        when(hitLogService.listCaseReferences(5L)).thenReturn(ref);

        mvc.perform(get("/api/knowledge/cases/5/references"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value(5))
                .andExpect(jsonPath("$.data.totalHits").value(3))
                .andExpect(jsonPath("$.data.recentHits.length()").value(1));
        verify(hitLogService).listCaseReferences(5L);
    }

    @Test
    @DisplayName("鉴权：两个查询接口均为只读，无写权限注解")
    void readOnly_endpoints() throws Exception {
        java.lang.reflect.Method m1 = KnowledgeHitLogController.class.getMethod("listDocumentHits", Long.class, int.class, int.class);
        org.springframework.security.access.prepost.PreAuthorize pa1 =
                m1.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        java.lang.reflect.Method m2 = KnowledgeHitLogController.class.getMethod("listCaseReferences", Long.class);
        org.springframework.security.access.prepost.PreAuthorize pa2 =
                m2.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        org.assertj.core.api.Assertions.assertThat(pa1).isNull();
        org.assertj.core.api.Assertions.assertThat(pa2).isNull();
    }
}
