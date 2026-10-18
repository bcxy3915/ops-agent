package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 上传质检冲突记录实体（knowledge_conflict 表，P1）
 * 新文档与已有文档发生标题相似/内容语义冲突时落库，供人工三态裁定。
 * status: PENDING(待裁定) / RESOLVED(已裁定)
 * decision: KEEP_NEW(以新为准) / KEEP_OLD(保留原值) / MANUAL(标记人工裁定)
 */
@Data
@TableName("knowledge_conflict")
public class KnowledgeConflictEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 引发冲突的文档（新上传/新编写） */
    @TableField("doc_id")
    private Long docId;

    /** 冲突匹配到的已有文档 */
    @TableField("matched_doc_id")
    private Long matchedDocId;

    /** 冲突类型：TITLE(标题相似) / CONTENT(内容语义相似) */
    @TableField("conflict_type")
    private String conflictType;

    /** 相似度 0-1 */
    @TableField("similarity")
    private BigDecimal similarity;

    /** 状态：PENDING / RESOLVED */
    @TableField("status")
    private String status;

    /** 三态裁定结果：KEEP_NEW / KEEP_OLD / MANUAL */
    @TableField("decision")
    private String decision;

    /** 裁定人 */
    @TableField("resolved_by")
    private String resolvedBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 裁定时间 */
    @TableField("resolved_at")
    private LocalDateTime resolvedAt;
}
