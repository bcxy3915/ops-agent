package com.example.opsaiagent.knowledge.dto;

import lombok.Data;

/**
 * 知识冲突三态裁定请求（P1）
 * decision：KEEP_NEW（以新为准）/ KEEP_OLD（保留原值）/ MANUAL（标记人工裁定）
 */
@Data
public class ConflictResolveRequest {

    /** 裁定结果：KEEP_NEW / KEEP_OLD / MANUAL */
    private String decision;
    /** 备注（可选） */
    private String remark;
}
