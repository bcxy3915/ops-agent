package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档版本历史实体（knowledge_document_version 表，P2）
 * 每次内容变更（编辑/审核发布/回滚）写入内容快照，支持版本列表与回滚。
 */
@Data
@TableName("knowledge_document_version")
public class KnowledgeDocumentVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识文档 ID */
    @TableField("doc_id")
    private Long docId;

    /** 版本号 */
    @TableField("version")
    private Integer version;

    /** 内容快照（raw_content） */
    @TableField("content_snapshot")
    private String contentSnapshot;

    /** 变更人 */
    @TableField("changed_by")
    private String changedBy;

    /** 变更原因 */
    @TableField("change_reason")
    private String changeReason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
