package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档分块实体（knowledge_chunk 表）
 * 文档按语义切分后的块，供检索测试 / 向量化引用定位。
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 ID */
    @TableField("doc_id")
    private Long docId;

    /** 块内序号 */
    @TableField("ordinal")
    private Integer ordinal;

    /** 分块文本 */
    @TableField("content")
    private String content;

    /** 所属章节/小节 */
    @TableField("section")
    private String section;

    /** 关联 vector_store 的向量 ID（如可用） */
    @TableField("vector_id")
    private String vectorId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
