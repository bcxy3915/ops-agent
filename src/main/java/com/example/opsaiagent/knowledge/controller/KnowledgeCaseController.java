package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.PageResult;
import com.example.opsaiagent.knowledge.dto.CaseReviseRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseDetailResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseSummaryResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeCaseService;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 故障案例接口（T19/T20/T21）
 * 案例列表/详情（五步闭环链路）、确认沉淀、修正沉淀、丢弃。
 */
@Tag(name = "知识库-故障案例", description = "故障案例的列表、详情、确认沉淀、修正沉淀与丢弃")
@RestController
@RequestMapping("/api/knowledge/cases")
@RequiredArgsConstructor
public class KnowledgeCaseController {

    private final KnowledgeCaseService caseService;

    @Operation(summary = "查询案例列表", description = "按状态筛选（PENDING/CONFIRMED/DISCARDED）与分页，所有角色可访问")
    @GetMapping
    public ApiResponse<PageResult<KnowledgeCaseSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(caseService.list(status, page, size));
    }

    @Operation(summary = "查询案例详情", description = "返回五步闭环链路（告警→快照→诊断→处置→恢复）、置信度、证据与脱敏状态，所有角色可访问")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeCaseDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(caseService.getById(id));
    }

    @Operation(summary = "确认沉淀案例", description = "PENDING → CONFIRMED，生成 CASE 层知识文档并分块向量化，需 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "CONFIRM_CASE", description = "确认沉淀故障案例")
    @PostMapping("/{id}/confirm")
    public ApiResponse<KnowledgeCaseDetailResponse> confirm(@PathVariable Long id) {
        return ApiResponse.success(caseService.confirm(id));
    }

    @Operation(summary = "修正并沉淀案例", description = "修正真实根因/有效处置/脱敏状态后确认沉淀，需 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "REVISE_CASE", description = "修正并沉淀故障案例")
    @PostMapping("/{id}/revise")
    public ApiResponse<KnowledgeCaseDetailResponse> revise(@PathVariable Long id,
                                                           @Valid @RequestBody CaseReviseRequest request) {
        return ApiResponse.success(caseService.revise(id, request));
    }

    @Operation(summary = "丢弃候选案例", description = "PENDING → DISCARDED，不再沉淀为知识，需 ADMIN 或 OPERATOR 角色")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @AuditLog(operation = "DISCARD_CASE", description = "丢弃故障案例候选")
    @PostMapping("/{id}/discard")
    public ApiResponse<Map<String, Long>> discard(@PathVariable Long id) {
        caseService.discard(id);
        return ApiResponse.success(Map.of("discarded", id));
    }
}
