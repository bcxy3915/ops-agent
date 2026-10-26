package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.knowledge.dto.VectorMetadataValidationResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeVectorMetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库向量 metadata 校验接口（迁移验收）。
 */
@Tag(name = "知识库-向量校验", description = "校验 vector_store metadata 的租户/可见性/审核字段")
@RestController
@RequestMapping("/api/knowledge/vector-metadata")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('knowledge:admin')")
public class KnowledgeVectorMetadataController {

    private final KnowledgeVectorMetadataService metadataService;

    @Operation(summary = "校验向量 metadata", description = "检查 tenantId/visibility/reviewStatus/owner 与内置知识、案例约定")
    @GetMapping("/validate")
    public ApiResponse<VectorMetadataValidationResponse> validate() {
        return ApiResponse.success(metadataService.validate());
    }
}
