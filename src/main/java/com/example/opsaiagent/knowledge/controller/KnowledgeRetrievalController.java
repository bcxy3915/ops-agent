package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalTestRequest;
import com.example.opsaiagent.knowledge.dto.RetrievalTestResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库检索测试接口（T18）
 * 混合检索验证（BM25 + 向量 + Rerank），返回结构化命中与高亮片段。
 */
@Tag(name = "知识库-检索测试", description = "验证 RAG 混合检索效果，返回命中片段与元数据")
@RestController
@RequestMapping("/api/knowledge/retrieval")
@RequiredArgsConstructor
public class KnowledgeRetrievalController {

    private final KnowledgeRetrievalService retrievalService;

    @Operation(summary = "检索测试", description = "混合检索并返回高亮片段/层级/状态/分数/版本，所有角色可访问")
    @RateLimit(key = "knowledgeRetrieval", limit = 60, period = 60, dimension = RateLimit.Dimension.USER)
    @PostMapping("/test")
    public ApiResponse<RetrievalTestResponse> test(@Valid @RequestBody RetrievalTestRequest request) {
        return ApiResponse.success(retrievalService.test(request));
    }
}
