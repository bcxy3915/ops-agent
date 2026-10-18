package com.example.opsaiagent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库总览统计响应（P1）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeStatsResponse {

    /** 知识文档总数 */
    private Long totalDocs;
    /** 待审核文档数 */
    private Long pendingDocs;
    /** 已超期文档数（review_at 已到期） */
    private Long expiredDocs;
    /** 零命中文档数（从未被引用） */
    private Long zeroHitDocs;
    /** 孤儿文档数（无分块内容，无法检索） */
    private Long orphanDocs;
    /** 故障案例总数 */
    private Long totalCases;
    /** 待沉淀案例数 */
    private Long pendingCases;
    /** 已沉淀案例数 */
    private Long confirmedCases;
    /** 待裁定冲突数 */
    private Long pendingConflicts;
    /** 本月命中/引用次数 */
    private Long monthHits;
}
