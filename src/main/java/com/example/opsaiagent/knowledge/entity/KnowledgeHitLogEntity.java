package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识命中/引用流水实体（knowledge_hit_log 表，P1）
 * 对话引用、检索测试等每次命中都会写入一条流水，
 * 用于月度命中统计、单篇命中反查、案例引用次数等运营指标。
 * hit_source: ASK(对话引用) / TEST(检索测试) / RETRIEVE(其他检索)
 */
@Data
@TableName("knowledge_hit_log")
public class KnowledgeHitLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 命中的知识文档 ID */
    @TableField("doc_id")
    private Long docId;

    /** 触发检索的问题 */
    @TableField("query")
    private String query;

    /** 命中来源：ASK / TEST / RETRIEVE */
    @TableField("hit_source")
    private String hitSource;

    /** 渠道标识（用户名 / 测试会话） */
    @TableField("channel")
    private String channel;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
