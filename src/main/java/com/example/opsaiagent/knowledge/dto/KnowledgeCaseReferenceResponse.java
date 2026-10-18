package com.example.opsaiagent.knowledge.dto;

import java.util.List;

/**
 * 案例引用统计响应 DTO（P2：案例引用统计）
 * 返回案例被检索命中的总次数与最近命中流水。
 */
public class KnowledgeCaseReferenceResponse {

    /** 案例 ID */
    private Long caseId;

    /** 案例对应的知识文档 ID（source 为 case:{caseId} 的文档） */
    private Long docId;

    /** 案例被命中的总次数 */
    private Long totalHits;

    /** 最近命中流水（按时间倒序，最多 10 条） */
    private List<KnowledgeHitLogResponse> recentHits;

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Long getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(Long totalHits) {
        this.totalHits = totalHits;
    }

    public List<KnowledgeHitLogResponse> getRecentHits() {
        return recentHits;
    }

    public void setRecentHits(List<KnowledgeHitLogResponse> recentHits) {
        this.recentHits = recentHits;
    }
}
