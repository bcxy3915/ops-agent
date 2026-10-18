package com.example.opsaiagent.knowledge.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库健康度明细响应 DTO（P2：健康度明细）
 * 聚合超期文档 / 零命中文档 / 孤儿文档三类明细列表。
 */
public class KnowledgeHealthResponse {

    /** 超期文档（review_at 已到期） */
    private List<KnowledgeHealthItem> expired;

    /** 零命中文档（hit_count 为空或 0） */
    private List<KnowledgeHealthItem> zeroHit;

    /** 孤儿文档（chunk_count 为空或 0，未向量化入库） */
    private List<KnowledgeHealthItem> orphan;

    public List<KnowledgeHealthItem> getExpired() {
        return expired;
    }

    public void setExpired(List<KnowledgeHealthItem> expired) {
        this.expired = expired;
    }

    public List<KnowledgeHealthItem> getZeroHit() {
        return zeroHit;
    }

    public void setZeroHit(List<KnowledgeHealthItem> zeroHit) {
        this.zeroHit = zeroHit;
    }

    public List<KnowledgeHealthItem> getOrphan() {
        return orphan;
    }

    public void setOrphan(List<KnowledgeHealthItem> orphan) {
        this.orphan = orphan;
    }

    /**
     * 健康度明细条目
     */
    public static class KnowledgeHealthItem {

        /** 文档 ID */
        private Long id;

        /** 文档标题 */
        private String title;

        /** 信任状态 */
        private String trustStatus;

        /** 命中次数 */
        private Long hitCount;

        /** 复核到期时间（可为空） */
        private LocalDateTime reviewAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getTrustStatus() {
            return trustStatus;
        }

        public void setTrustStatus(String trustStatus) {
            this.trustStatus = trustStatus;
        }

        public Long getHitCount() {
            return hitCount;
        }

        public void setHitCount(Long hitCount) {
            this.hitCount = hitCount;
        }

        public LocalDateTime getReviewAt() {
            return reviewAt;
        }

        public void setReviewAt(LocalDateTime reviewAt) {
            this.reviewAt = reviewAt;
        }
    }
}
