package com.example.opsaiagent.knowledge.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.common.api.PageResult;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeIndexStatusResponse;
import com.example.opsaiagent.knowledge.dto.KnowledgeOnlineCreateRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgePublishRequest;
import com.example.opsaiagent.knowledge.dto.KnowledgeReviewRequest;
import com.example.opsaiagent.knowledge.service.KnowledgeDocumentService;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 知识文档管理接口（T18）
 * 文档列表/详情/上传/审核发布/编辑/删除。
 */
@Tag(name = "知识库-文档管理", description = "知识文档的查询、上传、审核发布、编辑与删除")
@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    @Operation(summary = "查询文档列表", description = "支持关键词 / 层级 / 状态 / 服务筛选与分页，所有角色可访问")
    @PreAuthorize("hasAuthority('knowledge:read')")
    @GetMapping
    public ApiResponse<PageResult<KnowledgeDocumentResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String trustStatus,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(documentService.list(keyword, layer, trustStatus, service, page, size));
    }

    @Operation(summary = "查询文档详情", description = "返回文档元数据与分块列表，所有角色可访问")
    @PreAuthorize("hasAuthority('knowledge:read')")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDocumentResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(documentService.getById(id));
    }

    @Operation(summary = "上传知识文档", description = "支持 md/pdf/txt/docx，解析后保存为草稿，需 knowledge:write")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:write')")
    @AuditLog(operation = "UPLOAD_KNOWLEDGE", description = "上传知识文档")
    @PostMapping("/upload")
    public ApiResponse<KnowledgeDocumentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(defaultValue = "PRIVATE") String layer,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String serviceIds,
            @RequestParam(required = false) String tags) {
        return ApiResponse.success(documentService.upload(
                file, title, layer, owner, split(serviceIds), split(tags)));
    }

    @Operation(summary = "在线编写知识文档", description = "直接填写标题与正文创建草稿，需 knowledge:write")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:write')")
    @AuditLog(operation = "ONLINE_CREATE_KNOWLEDGE", description = "在线编写知识文档")
    @PostMapping("/online-create")
    public ApiResponse<KnowledgeDocumentResponse> onlineCreate(@Valid @RequestBody KnowledgeOnlineCreateRequest request) {
        return ApiResponse.success(documentService.onlineCreate(request));
    }

    @Operation(summary = "自动超期判定", description = "扫描复核到期的 VERIFIED 文档并标记为 EXPIRED，需 knowledge:admin")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:admin')")
    @AuditLog(operation = "UPDATE_KNOWLEDGE", description = "自动超期判定")
    @PostMapping("/expire-scan")
    public ApiResponse<Map<String, Integer>> expireScan() {
        return ApiResponse.success(Map.of("expired", documentService.expireOverdueDocuments()));
    }

    @Operation(summary = "发布审核（兼容旧接口）", description = "通过则向量化入库（VERIFIED），驳回退回个人，需 knowledge:review")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:review')")
    @AuditLog(operation = "PUBLISH_KNOWLEDGE", description = "审核发布知识文档")
    @PostMapping("/{id}/publish")
    public ApiResponse<KnowledgeDocumentResponse> publish(@PathVariable Long id,
                                                          @Valid @RequestBody KnowledgePublishRequest request) {
        return ApiResponse.success(documentService.publish(id, Boolean.TRUE.equals(request.getApproved())));
    }

    @Operation(summary = "提交审核", description = "作者将草稿/已驳回文档提交部门审核，需 knowledge:write")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:write')")
    @AuditLog(operation = "SUBMIT_KNOWLEDGE_REVIEW", description = "提交知识审核")
    @PostMapping("/{id}/submit")
    public ApiResponse<KnowledgeDocumentResponse> submit(@PathVariable Long id) {
        return ApiResponse.success(documentService.submitForReview(id));
    }

    @Operation(summary = "审核知识文档", description = "部门/企业审核，需 knowledge:review")
    @RateLimit(key = "knowledgeReview", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:review')")
    @AuditLog(operation = "REVIEW_KNOWLEDGE", description = "审核知识文档")
    @PostMapping("/{id}/review")
    public ApiResponse<KnowledgeDocumentResponse> review(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeReviewRequest request) {
        return ApiResponse.success(documentService.review(id, request));
    }

    @Operation(summary = "编辑知识文档", description = "内容变更会下线旧向量并重置为草稿，需 knowledge:write")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:write')")
    @AuditLog(operation = "UPDATE_KNOWLEDGE", description = "编辑知识文档")
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeDocumentResponse> update(@PathVariable Long id,
                                                         @Valid @RequestBody KnowledgeDocumentUpdateRequest request) {
        return ApiResponse.success(documentService.update(id, request));
    }

    @Operation(summary = "查询文档索引状态", description = "轻量返回 publishStatus / publishError / 任务重试次数，供前端轮询入库进度")
    @PreAuthorize("hasAuthority('knowledge:read')")
    @GetMapping("/{id}/index-status")
    public ApiResponse<KnowledgeIndexStatusResponse> indexStatus(@PathVariable Long id) {
        return ApiResponse.success(documentService.indexStatus(id));
    }

    @Operation(summary = "重建文档索引", description = "仅已通过审核且已发布/入库失败的文档可重试，不改变审核结论")
    @RateLimit(key = "knowledgeReview", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAnyAuthority('knowledge:write', 'knowledge:review')")
    @AuditLog(operation = "REINDEX_KNOWLEDGE", description = "重建知识文档索引")
    @PostMapping("/{id}/reindex")
    public ApiResponse<KnowledgeDocumentResponse> reindex(@PathVariable Long id) {
        return ApiResponse.success(documentService.reindex(id));
    }

    @Operation(summary = "删除知识文档", description = "作者或知识管理员删除文档及其分块与向量，需 knowledge:write")
    @RateLimit(key = "knowledgeWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('knowledge:write')")
    @AuditLog(operation = "DELETE_KNOWLEDGE", description = "删除知识文档")
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Long>> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ApiResponse.success(Map.of("deleted", id));
    }

    /**
     * 逗号分隔字符串转列表（空则 null）
     */
    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
