package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeVersionResponse;
import com.example.opsaiagent.knowledge.service.KnowledgeDocumentService;
import com.example.opsaiagent.knowledge.service.KnowledgeVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识文档版本历史与回滚控制器（P2）
 * 提供版本列表查询与版本回滚接口。
 */
@Tag(name = "知识版本")
@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeVersionController {

    private final KnowledgeVersionService versionService;
    private final KnowledgeDocumentService documentService;

    /**
     * 查询指定文档的版本历史（只读，所有角色可访问）
     * @param docId 文档 ID
     * @return 版本列表（按版本号降序）
     */
    @Operation(summary = "查询知识文档版本历史")
    @PreAuthorize("hasAuthority('knowledge:read')")
    @GetMapping("/{docId}/versions")
    public ApiResponse<List<KnowledgeVersionResponse>> listVersions(@PathVariable Long docId) {
        return ApiResponse.success(versionService.listVersions(docId));
    }

    /**
     * 回滚文档到指定历史版本
     * @param docId 文档 ID
     * @param versionId 目标版本记录 ID
     * @return 回滚后的文档响应
     */
    @Operation(summary = "回滚知识文档到历史版本")
    @PostMapping("/{docId}/versions/{versionId}/rollback")
    @PreAuthorize("hasAuthority('knowledge:write')")
    @AuditLog(operation = "ROLLBACK_KNOWLEDGE", description = "回滚知识文档版本")
    public ApiResponse<KnowledgeDocumentResponse> rollback(@PathVariable Long docId,
                                                           @PathVariable Long versionId) {
        return ApiResponse.success(documentService.rollback(docId, versionId));
    }
}
