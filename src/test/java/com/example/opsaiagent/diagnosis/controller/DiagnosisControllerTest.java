package com.example.opsaiagent.diagnosis.controller;

import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.service.DiagnosisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 智能诊断接口测试（T16）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLM 智能诊断接口")
class DiagnosisControllerTest {

    @Mock
    private DiagnosisService diagnosisService;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DiagnosisController(diagnosisService))
                .build();
        lenient().when(diagnosisService.parseEvidence(any())).thenReturn(List.of("CPU 95%"));
        lenient().when(diagnosisService.parseSuggestions(any())).thenReturn(List.of("扩容"));
    }

    private DiagnosisRecordEntity entity(Long id) {
        DiagnosisRecordEntity e = new DiagnosisRecordEntity();
        e.setId(id);
        e.setAlertEventId(200L);
        e.setSnapshotId(100L);
        e.setRuleName("cpu-high");
        e.setService("svc-a");
        e.setMetric("cpu");
        e.setStatus("SUCCESS");
        e.setRootCause("CPU 打满");
        e.setEvidence("[\"CPU 95%\"]");
        e.setConfidence(85);
        e.setSuggestions("[\"扩容\"]");
        return e;
    }

    @Test
    @DisplayName("GET /api/diagnoses 列表")
    void list() throws Exception {
        when(diagnosisService.list(isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(entity(1L)));

        mvc.perform(get("/api/diagnoses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].rootCause").value("CPU 打满"))
                .andExpect(jsonPath("$.data[0].evidence[0]").value("CPU 95%"))
                .andExpect(jsonPath("$.data[0].confidence").value(85));
    }

    @Test
    @DisplayName("GET /api/diagnoses 支持过滤参数")
    void list_filtered() throws Exception {
        when(diagnosisService.list(eq("svc-a"), eq(200L), eq(10L), eq(20)))
                .thenReturn(List.of(entity(2L)));

        mvc.perform(get("/api/diagnoses")
                        .param("service", "svc-a")
                        .param("alertEventId", "200")
                        .param("ruleId", "10")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(2));
    }

    @Test
    @DisplayName("GET /api/diagnoses/{id} 详情")
    void detail() throws Exception {
        when(diagnosisService.getById(1L)).thenReturn(entity(1L));

        mvc.perform(get("/api/diagnoses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootCause").value("CPU 打满"))
                .andExpect(jsonPath("$.data.suggestions[0]").value("扩容"));
    }

    @Test
    @DisplayName("POST /api/diagnoses/snapshot/{snapshotId} 手动触发诊断")
    void triggerDiagnosis() throws Exception {
        when(diagnosisService.diagnoseBySnapshot(100L)).thenReturn(entity(3L));

        mvc.perform(post("/api/diagnoses/snapshot/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        verify(diagnosisService).diagnoseBySnapshot(100L);
    }

    @Test
    @DisplayName("POST /api/diagnoses/{id}/follow-up 追问")
    void followUp() throws Exception {
        DiagnosisRecordEntity follow = entity(4L);
        follow.setQuestion("怎么止损？");
        follow.setAnswer("回滚变更");
        when(diagnosisService.followUp(anyLong(), eq("怎么止损？"))).thenReturn(follow);

        mvc.perform(post("/api/diagnoses/1/follow-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("question", "怎么止损？"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question").value("怎么止损？"))
                .andExpect(jsonPath("$.data.answer").value("回滚变更"));
    }

    @Test
    @DisplayName("POST /api/diagnoses/{id}/follow-up 空问题返回 400")
    void followUp_blankQuestion() throws Exception {
        mvc.perform(post("/api/diagnoses/1/follow-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("question", ""))))
                .andExpect(status().isBadRequest());
    }
}
