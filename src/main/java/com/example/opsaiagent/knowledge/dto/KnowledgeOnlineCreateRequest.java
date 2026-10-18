package com.example.opsaiagent.knowledge.dto;

import lombok.Data;

import java.util.List;

/**
 * 在线编写知识文档请求（P1）
 * 与上传文档同流程：先保存为待审核，审核通过后分块向量化入库。
 */
@Data
public class KnowledgeOnlineCreateRequest {

    /** 文档标题（必填） */
    private String title;
    /** 文档正文（必填，markdown 文本） */
    private String content;
    /** 负责人（可选） */
    private String owner;
    /** 适用服务列表（可选） */
    private List<String> serviceIds;
    /** 标签列表（可选） */
    private List<String> tags;
}
