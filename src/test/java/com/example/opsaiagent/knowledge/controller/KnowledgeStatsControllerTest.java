package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.exception.GlobalExceptionHandler;
import com.example.opsaiagent.knowledge.dto.KnowledgeCoverageResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHealthResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeStatsResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeTodoResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库统计接口测试（P1）
 * 覆盖：/stats /coverage /todos 返回结构、只读接口无写权限要求。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识库统计接口")
class KnowledgeStatsControllerTest {

    @Mock
    private KnowledgeStatsService statsService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeStatsController(statsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/knowledge/stats：返回总览统计")
    void stats() throws Exception {
        when(statsService.stats()).thenReturn(KnowledgeStatsResponse.builder()
                .totalDocs(10L).pendingDocs(3L).expiredDocs(2L).zeroHitDocs(4L).orphanDocs(1L)
                .totalCases(5L).pendingCases(2L).confirmedCases(3L).pendingConflicts(1L).monthHits(7L)
                .build());

        mvc.perform(get("/api/knowledge/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalDocs").value(10))
                .andExpect(jsonPath("$.data.pendingDocs").value(3))
                .andExpect(jsonPath("$.data.monthHits").value(7));
    }

    @Test
    @DisplayName("GET /api/knowledge/coverage：返回服务覆盖明细")
    void coverage() throws Exception {
        when(statsService.coverage()).thenReturn(KnowledgeCoverageResponse.builder()
                .totalServices(2L).coveredServices(1L).coverageRate(0.5)
                .details(List.of(KnowledgeCoverageResponse.ServiceCoverageItem.builder()
                        .service("todo-service").docCount(3L).verifiedCount(2L).build()))
                .build());

        mvc.perform(get("/api/knowledge/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalServices").value(2))
                .andExpect(jsonPath("$.data.coverageRate").value(0.5))
                .andExpect(jsonPath("$.data.details[0].service").value("todo-service"));
    }

    @Test
    @DisplayName("GET /api/knowledge/todos：返回四类目待办")
    void todos() throws Exception {
        when(statsService.todos()).thenReturn(KnowledgeTodoResponse.builder()
                .pendingDocs(List.of(KnowledgeTodoResponse.KnowledgeTodoItem.builder()
                        .id(1L).title("待审核文档").itemType("DOCUMENT").status("PENDING").build()))
                .pendingCases(List.of(KnowledgeTodoResponse.KnowledgeTodoItem.builder()
                        .id(2L).title("待沉淀案例").itemType("CASE").status("PENDING").build()))
                .conflicts(List.of())
                .expiredDocs(List.of())
                .build());

        mvc.perform(get("/api/knowledge/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingDocs[0].itemType").value("DOCUMENT"))
                .andExpect(jsonPath("$.data.pendingCases[0].itemType").value("CASE"))
                .andExpect(jsonPath("$.data.conflicts").isEmpty());
    }

    @Test
    @DisplayName("GET /api/knowledge/health：返回健康度三类明细")
    void health() throws Exception {
        KnowledgeHealthResponse health = new KnowledgeHealthResponse();
        KnowledgeHealthResponse.KnowledgeHealthItem expired = new KnowledgeHealthResponse.KnowledgeHealthItem();
        expired.setId(1L);
        expired.setTitle("过期手册");
        expired.setTrustStatus("VERIFIED");
        health.setExpired(List.of(expired));
        health.setZeroHit(List.of());
        health.setOrphan(List.of());
        when(statsService.health()).thenReturn(health);

        mvc.perform(get("/api/knowledge/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expired[0].title").value("过期手册"))
                .andExpect(jsonPath("$.data.zeroHit").isEmpty())
                .andExpect(jsonPath("$.data.orphan").isEmpty());
    }

    @Test
    @DisplayName("统计接口为只读：方法上无 @PreAuthorize（登录用户均可访问）")
    void readEndpoints_openToAllRoles() throws Exception {
        java.lang.reflect.Method stats = KnowledgeStatsController.class.getMethod("stats");
        java.lang.reflect.Method coverage = KnowledgeStatsController.class.getMethod("coverage");
        java.lang.reflect.Method todos = KnowledgeStatsController.class.getMethod("todos");
        java.lang.reflect.Method health = KnowledgeStatsController.class.getMethod("health");
        assertThat(stats.getAnnotation(PreAuthorize.class)).isNull();
        assertThat(coverage.getAnnotation(PreAuthorize.class)).isNull();
        assertThat(todos.getAnnotation(PreAuthorize.class)).isNull();
        assertThat(health.getAnnotation(PreAuthorize.class)).isNull();
    }
}
