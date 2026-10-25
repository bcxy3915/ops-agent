package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.knowledge.dto.ConflictResolveRequest;
import com.example.opsaiagent.knowledge.dto.ConflictResolveResponse;
import com.example.opsaiagent.knowledge.dto.QualityCheckRequest;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识文档上传质检接口（P1）
 * check：上传前质检（冲突/过时/脱敏预览）；resolve：冲突三态裁定。
 * 均为写操作，仅 ADMIN / OPERATOR 可访问。
 */
@Tag(name = "知识库质检")
@RestController
@RequestMapping("/api/knowledge/quality")
@RequiredArgsConstructor
public class KnowledgeQualityController {

    private final KnowledgeQualityService qualityService;

    /**
     * 上传质检：冲突检测 + 过时检测 + 敏感信息脱敏预览
     * @param request 质检请求
     * @return 质检结果
     */
    @PostMapping("/check")
    @Operation(summary = "知识文档上传质检")
    @PreAuthorize("hasAuthority('knowledge:write')")
    @AuditLog(operation = "UPLOAD_KNOWLEDGE")
    public ApiResponse<QualityCheckResponse> check(@RequestBody QualityCheckRequest request) {
        return ApiResponse.success(qualityService.check(request));
    }

    /**
     * 冲突三态裁定
     * @param conflictId 冲突记录 ID
     * @param request 裁定请求（KEEP_NEW / KEEP_OLD / MANUAL）
     * @return 裁定结果
     */
    @PostMapping("/conflicts/{conflictId}/resolve")
    @Operation(summary = "知识冲突三态裁定")
    @PreAuthorize("hasAuthority('knowledge:review')")
    @AuditLog(operation = "CONFLICT_RESOLVE")
    public ApiResponse<ConflictResolveResponse> resolve(@PathVariable Long conflictId,
                                                        @RequestBody ConflictResolveRequest request) {
        return ApiResponse.success(qualityService.resolve(conflictId, request));
    }
}
