package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档实体（knowledge_document 表）
 * 记录知识文档元数据：内置手册 / 私有上传 / 案例沉淀三层知识，
 * 以及信任状态（已验证 / 待审核 / AI初稿 / 已失效）与版本。
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档标题 */
    @TableField("title")
    private String title;

    /** 文档类型：MARKDOWN / PDF / TXT / DOCX */
    @TableField("doc_type")
    private String docType;

    /** 知识层级：BUILTIN / PRIVATE / CASE */
    @TableField("layer")
    private String layer;

    /** 信任状态：VERIFIED / PENDING / AI_DRAFT / EXPIRED */
    @TableField("trust_status")
    private String trustStatus;

    /** 版本号，每次内容变更 +1 */
    @TableField("version")
    private Integer version;

    /** 负责人 */
    @TableField("owner")
    private String owner;

    /** 适用服务列表（JSON 数组字符串） */
    @TableField("service_ids")
    private String serviceIds;

    /** 标签（JSON 数组字符串） */
    @TableField("tags")
    private String tags;

    /** 被引用命中次数 */
    @TableField("hit_count")
    private Long hitCount;

    /** 来源标识（文件名 / 上传名） */
    @TableField("source")
    private String source;

    /** 文件存储路径（内置文档用 classpath 标识） */
    @TableField("file_path")
    private String filePath;

    /** 原始全文（供预览 / 分块） */
    @TableField("raw_content")
    private String rawContent;

    /** 分块数量 */
    @TableField("chunk_count")
    private Integer chunkCount;

    /** 提交审核时间 */
    @TableField("submitted_at")
    private LocalDateTime submittedAt;

    /** 最近审核时间 */
    @TableField("last_review_at")
    private LocalDateTime lastReviewAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
