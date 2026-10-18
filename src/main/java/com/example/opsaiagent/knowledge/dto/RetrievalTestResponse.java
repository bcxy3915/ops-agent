package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 检索测试结果（T18）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalTestResponse {

    /** 查询文本 */
    private String query;
    /** 请求条数 */
    private Integer topK;
    /** 实际命中条数 */
    private Integer count;
    /** 命中列表（按相关度降序） */
    private List<RetrievalHitResponse> hits;
}
