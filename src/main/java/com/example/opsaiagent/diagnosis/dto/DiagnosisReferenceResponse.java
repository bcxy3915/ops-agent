package com.example.opsaiagent.diagnosis.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 诊断引用来源（P1 规格 4.5 诊断来源溯源）
 * 记录一次诊断生成时 RAG 检索命中的知识条目 / 历史案例。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisReferenceResponse {

    /** 引用类型：BASE_DOC 内置底座 / KNOWLEDGE_DOC 私有知识文档 / CASE 历史案例 */
    private String refType;
    /** 引用对象 ID（文档 ID 或案例 ID） */
    private Long refId;
    /** 标题（文档名 / 案例标题） */
    private String title;
    /** 章节（可选） */
    private String section;
    /** 命中片段（snippet，≤2 行展示由前端截断） */
    private String snippet;
    /** 相关度（0~1） */
    private Double score;
    /** 知识层级（BUILTIN / PRIVATE / CASE） */
    private String layer;
    /** 版本号（如 v2.4） */
    private String version;
    /** 作者 / 负责人（文档 owner） */
    private String author;
    /** 案例被引用次数（案例才有） */
    private Long citedCount;
}
