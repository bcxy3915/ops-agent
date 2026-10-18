package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.exception.GlobalExceptionHandler;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalTestResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 检索测试接口测试（T18）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("检索测试接口")
class KnowledgeRetrievalControllerTest {

    @Mock
    private KnowledgeRetrievalService retrievalService;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeRetrievalController(retrievalService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST 检索测试：正常返回")
    void test_ok() throws Exception {
        when(retrievalService.test(any())).thenReturn(RetrievalTestResponse.builder()
                .query("故障")
                .topK(5)
                .count(1)
                .hits(List.of(RetrievalHitResponse.builder()
                        .docId(1L)
                        .title("手册")
                        .layer("BUILTIN")
                        .trustStatus("VERIFIED")
                        .version(1)
                        .snippet("<mark>故障</mark>排查")
                        .score(1.0)
                        .build()))
                .build());

        mvc.perform(post("/api/knowledge/retrieval/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("query", "故障", "topK", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.hits[0].title").value("手册"))
                .andExpect(jsonPath("$.data.hits[0].snippet").value("<mark>故障</mark>排查"));
        verify(retrievalService).test(any());
    }

    @Test
    @DisplayName("POST 检索测试：缺少 query 返回参数错误")
    void test_missingQuery() throws Exception {
        mvc.perform(post("/api/knowledge/retrieval/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    @DisplayName("POST 检索测试：topK 超出范围返回参数错误")
    void test_topKOutOfRange() throws Exception {
        mvc.perform(post("/api/knowledge/retrieval/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("query", "q", "topK", 99))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }
}
