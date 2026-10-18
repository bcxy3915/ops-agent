package com.example.opsaiagent.knowledge.dto;

import lombok.Data;

/**
 * 知识文档上传质检请求（P1）
 */
@Data
public class QualityCheckRequest {

    /** 文档标题 */
    private String title;
    /** 文档正文（待质检内容） */
    private String content;
    /** 文档类型（MARKDOWN/DOCX/PDF/TEXT，可选，默认 MARKDOWN） */
    private String docType;
}
