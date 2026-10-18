package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.dto.PageResult;
import com.example.opsaiagent.exception.GlobalExceptionHandler;
import com.example.opsaiagent.knowledge.dto.CaseFlowStep;
import com.example.opsaiagent.knowledge.dto.CaseReviseRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseDetailResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseSummaryResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeCaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 故障案例接口测试（T19/T20/T21）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("故障案例接口")
class KnowledgeCaseControllerTest {

    @Mock
    private KnowledgeCaseService caseService;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeCaseController(caseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private KnowledgeCaseSummaryResponse summary(Long id, String status) {
        return KnowledgeCaseSummaryResponse.builder()
                .id(id)
                .sourceAlertId(100L)
                .diagnosisRecordId(200L)
                .service("order-service")
                .title("数据库连接池耗尽导致接口超时")
                .confidence(85)
                .status(status)
                .maskedStatus("NONE")
                .durationMin(25)
                .createdAt(LocalDateTime.of(2026, 1, 2, 10, 0))
                .build();
    }

    private KnowledgeCaseDetailResponse detail(Long id, String status) {
        return KnowledgeCaseDetailResponse.builder()
                .id(id)
                .sourceAlertId(100L)
                .diagnosisRecordId(200L)
                .service("order-service")
                .title("数据库连接池耗尽导致接口超时")
                .symptom("接口 RT 飙升至 3000ms")
                .rootCause("数据库连接数达到上限")
                .realRootCause("数据库连接数达到上限")
                .evidence(List.of("活跃线程 200/200", "DB 连接数 80/50"))
                .effectiveAction("重启数据库连接池并扩容")
                .confidence(85)
                .maskedStatus("NONE")
                .status(status)
                .durationMin(25)
                .flow(List.of(
                        CaseFlowStep.builder().step("ALERT").title("告警触发").content("接口超时").build(),
                        CaseFlowStep.builder().step("RECOVERY").title("闭环恢复").content("告警已恢复").build()))
                .build();
    }

    // ==================== GET /api/knowledge/cases ====================

    @Test
    @DisplayName("GET 列表：无参数返回分页结果")
    void list_default() throws Exception {
        when(caseService.list(isNull(), anyInt(), anyInt()))
                .thenReturn(PageResult.<KnowledgeCaseSummaryResponse>builder()
                        .records(List.of(summary(1L, "PENDING")))
                        .total(1).page(1).size(10).pages(1).build());

        mvc.perform(get("/api/knowledge/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("数据库连接池耗尽导致接口超时"))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET 列表：按状态筛选")
    void list_byStatus() throws Exception {
        when(caseService.list(any(String.class), anyInt(), anyInt()))
                .thenReturn(PageResult.<KnowledgeCaseSummaryResponse>builder()
                        .records(List.of(summary(2L, "CONFIRMED")))
                        .total(1).page(1).size(10).pages(1).build());

        mvc.perform(get("/api/knowledge/cases").param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].status").value("CONFIRMED"));
    }

    // ==================== GET /api/knowledge/cases/{id} ====================

    @Test
    @DisplayName("GET 详情：返回五步链路")
    void detail_ok() throws Exception {
        when(caseService.getById(1L)).thenReturn(detail(1L, "PENDING"));

        mvc.perform(get("/api/knowledge/cases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.evidence[0]").value("活跃线程 200/200"))
                .andExpect(jsonPath("$.data.flow[0].step").value("ALERT"))
                .andExpect(jsonPath("$.data.flow[1].step").value("RECOVERY"));
    }

    // ==================== POST /api/knowledge/cases/{id}/confirm ====================

    @Test
    @DisplayName("POST 确认沉淀：返回确认后详情")
    void confirm_ok() throws Exception {
        when(caseService.confirm(1L)).thenReturn(detail(1L, "CONFIRMED"));

        mvc.perform(post("/api/knowledge/cases/1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    // ==================== POST /api/knowledge/cases/{id}/revise ====================

    @Test
    @DisplayName("POST 修正沉淀：带修正字段")
    void revise_ok() throws Exception {
        CaseReviseRequest req = new CaseReviseRequest();
        req.setRealRootCause("修正后的根因");
        req.setEffectiveAction("修正后的处置");
        req.setMaskedStatus("PARTIAL");
        when(caseService.revise(any(Long.class), any(CaseReviseRequest.class)))
                .thenReturn(detail(1L, "CONFIRMED"));

        mvc.perform(post("/api/knowledge/cases/1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("POST 修正沉淀：字段超长返回参数错误")
    void revise_invalidField() throws Exception {
        CaseReviseRequest req = new CaseReviseRequest();
        req.setRealRootCause("x".repeat(2001));

        mvc.perform(post("/api/knowledge/cases/1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ==================== POST /api/knowledge/cases/{id}/discard ====================

    @Test
    @DisplayName("POST 丢弃：返回被丢弃的 id")
    void discard_ok() throws Exception {
        doNothing().when(caseService).discard(1L);

        mvc.perform(post("/api/knowledge/cases/1/discard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.discarded").value(1));

        verify(caseService).discard(1L);
    }

    // ==================== 鉴权注解 ====================

    @Test
    @DisplayName("鉴权：沉淀/修正/丢弃要求 ADMIN/OPERATOR")
    void writeEndpoints_requireAdminOrOperator() throws Exception {
        assertWriteRequires("confirm", Long.class);
        assertWriteRequires("revise", Long.class, CaseReviseRequest.class);
        assertWriteRequires("discard", Long.class);
    }

    @Test
    @DisplayName("鉴权：案例列表/详情不要求写权限（VIEWER 可读）")
    void readEndpoints_openToAllRoles() throws Exception {
        assertThat(KnowledgeCaseController.class
                .getMethod("list", String.class, int.class, int.class)
                .getAnnotation(PreAuthorize.class)).isNull();
        assertThat(KnowledgeCaseController.class
                .getMethod("detail", Long.class)
                .getAnnotation(PreAuthorize.class)).isNull();
    }

    private void assertWriteRequires(String methodName, Class<?>... params) throws NoSuchMethodException {
        java.lang.reflect.Method m = KnowledgeCaseController.class.getMethod(methodName, params);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).contains("ADMIN").contains("OPERATOR");
    }
}
