package com.example.opsaiagent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文档响应
 * 列表 / 详情共用；详情时 chunks 非空。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentResponse {

    private Long id;
    private String title;
    private String docType;
    private String layer;
    private String trustStatus;
    private String tenantId;
    private Long deptId;
    private String visibility;
    private String reviewStatus;
    private String reviewLevel;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewNote;
    private Integer version;
    private String owner;
    private List<String> serviceIds;
    private List<String> tags;
    private Long hitCount;
    private String source;
    private String filePath;
    private String rawContent;
    private Integer chunkCount;
    private LocalDateTime submittedAt;
    private LocalDateTime lastReviewAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 分块列表（仅详情返回） */
    private List<KnowledgeChunkResponse> chunks;
}
