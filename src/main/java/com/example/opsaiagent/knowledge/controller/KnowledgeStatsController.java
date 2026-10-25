package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeCoverageResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHealthResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeStatsResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeTodoResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库统计聚合接口（P1）
 * 总览统计 / 服务覆盖度 / 待办聚合，均为只读接口（登录用户可访问）。
 */
@Tag(name = "知识库统计")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('knowledge:read')")
public class KnowledgeStatsController {

    private final KnowledgeStatsService statsService;

    /**
     * 知识库总览统计
     * @return 文档/案例/冲突/本月命中/健康度概览计数
     */
    @GetMapping("/stats")
    @Operation(summary = "知识库总览统计")
    public ApiResponse<KnowledgeStatsResponse> stats() {
        return ApiResponse.success(statsService.stats());
    }

    /**
     * 知识覆盖度（按服务）
     * @return 服务级覆盖率与各服务文档明细
     */
    @GetMapping("/coverage")
    @Operation(summary = "知识覆盖度（按服务）")
    public ApiResponse<KnowledgeCoverageResponse> coverage() {
        return ApiResponse.success(statsService.coverage());
    }

    /**
     * 知识库待办聚合
     * @return 待审核文档 / 待沉淀案例 / 冲突待裁定 / 超期文档
     */
    @GetMapping("/todos")
    @Operation(summary = "知识库待办聚合")
    public ApiResponse<KnowledgeTodoResponse> todos() {
        return ApiResponse.success(statsService.todos());
    }

    /**
     * 知识库健康度明细（超期 / 零命中 / 孤儿文档）
     * @return 三类明细列表
     */
    @GetMapping("/health")
    @Operation(summary = "知识库健康度明细")
    public ApiResponse<KnowledgeHealthResponse> health() {
        return ApiResponse.success(statsService.health());
    }
}
