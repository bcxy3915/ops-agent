package com.example.opsaiagent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档索引状态响应（轻量，供前端轮询"入库中"是否完成）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexStatusResponse {

    private Long docId;
    /** 索引发布状态：UNPUBLISHED / BUILDING / FAILED / PUBLISHED */
    private String publishStatus;
    /** 最近一次索引失败原因 */
    private String publishError;
    /** 索引发布完成时间 */
    private LocalDateTime publishedAt;
    private Integer chunkCount;
    /** 最近一条索引任务的已尝试次数；无任务时为 null */
    private Integer taskAttempts;
    /** 最近一条索引任务的最大尝试次数；无任务时为 null */
    private Integer taskMaxAttempts;
}
