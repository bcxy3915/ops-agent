package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.PageResult;
import com.example.opsaiagent.knowledge.dto.KnowledgeCaseReferenceResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeHitLogResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeHitLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识命中流水控制器（P2）
 * 单篇命中反查与案例引用统计（只读，所有角色可访问）。
 */
@Tag(name = "知识命中流水")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeHitLogController {

    private final KnowledgeHitLogService hitLogService;

    /**
     * 分页查询单篇文档的命中流水
     * @param docId 文档 ID
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 命中流水分页结果
     */
    @Operation(summary = "查询知识文档命中流水")
    @GetMapping("/documents/{docId}/hits")
    public ApiResponse<PageResult<KnowledgeHitLogResponse>> listDocumentHits(
            @PathVariable Long docId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(hitLogService.listByDocument(docId, page, size));
    }

    /**
     * 案例引用统计：案例被检索命中的总次数与最近命中流水
     * @param caseId 案例 ID
     * @return 引用统计
     */
    @Operation(summary = "统计故障案例被引用情况")
    @GetMapping("/cases/{caseId}/references")
    public ApiResponse<KnowledgeCaseReferenceResponse> listCaseReferences(@PathVariable Long caseId) {
        return ApiResponse.success(hitLogService.listCaseReferences(caseId));
    }
}
