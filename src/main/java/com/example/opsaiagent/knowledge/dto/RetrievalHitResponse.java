package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 检索命中项（T18）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalHitResponse {

    /** 文档 ID（内置文档可反查得到） */
    private Long docId;
    /** 文档标题 */
    private String title;
    /** 知识层级 BUILTIN/PRIVATE/CASE */
    private String layer;
    /** 信任状态 VERIFIED/PENDING/AI_DRAFT/EXPIRED */
    private String trustStatus;
    /** 版本号 */
    private Integer version;
    /** 来源标识（内置文件名或 upload:{docId}） */
    private String source;
    /** 章节 */
    private String section;
    /** 分块 ID */
    private Long chunkId;
    /** 片段（命中关键词 <mark> 高亮） */
    private String snippet;
    /** 展示相关度（0~1，相对排序权重） */
    private Double score;
}
