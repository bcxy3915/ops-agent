package com.example.opsaiagent.knowledge.dto;

import java.time.LocalDateTime;

/**
 * 知识文档版本响应 DTO（P2：版本历史/回滚）
 * 用于向客户端返回一条版本快照记录。
 */
public class KnowledgeVersionResponse {

    /** 版本记录 ID */
    private Long id;

    /** 版本号（同文档内自增） */
    private Integer version;

    /** 内容快照 */
    private String contentSnapshot;

    /** 变更人 */
    private String changedBy;

    /** 变更原因（ONLINE_CREATE / UPDATE / ROLLBACK / ROLLBACK_BACKUP 等） */
    private String changeReason;

    /** 创建时间 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getContentSnapshot() {
        return contentSnapshot;
    }

    public void setContentSnapshot(String contentSnapshot) {
        this.contentSnapshot = contentSnapshot;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
