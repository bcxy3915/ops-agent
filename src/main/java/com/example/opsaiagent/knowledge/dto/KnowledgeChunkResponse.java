package com.example.opsaiagent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识文档分块响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkResponse {

    private Long id;
    private Long docId;
    private String tenantId;
    private Long deptId;
    private Integer ordinal;
    private String content;
    private String section;
    private String vectorId;
}
