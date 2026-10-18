package com.example.opsaiagent.knowledge.dto;

import java.time.LocalDateTime;

/**
 * 知识命中流水响应 DTO（P2：单篇命中反查）
 */
public class KnowledgeHitLogResponse {

    /** 流水 ID */
    private Long id;

    /** 命中的知识文档 ID */
    private Long docId;

    /** 触发检索的问题 */
    private String query;

    /** 命中来源（ASK / TEST / RETRIEVE） */
    private String hitSource;

    /** 渠道标识（用户名或测试标识，可空） */
    private String channel;

    /** 命中时间 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getHitSource() {
        return hitSource;
    }

    public void setHitSource(String hitSource) {
        this.hitSource = hitSource;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
