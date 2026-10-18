package com.example.opsaiagent.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 知识文档发布审核请求
 */
@Data
public class KnowledgePublishRequest {

    /** true=审核通过（向量化入库）；false=驳回（保持待处理） */
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    /** 审核意见（可选） */
    private String comment;
}
