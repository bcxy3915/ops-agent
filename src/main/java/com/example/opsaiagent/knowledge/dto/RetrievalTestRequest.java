package com.example.opsaiagent.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 检索测试请求（T18）
 */
@Data
public class RetrievalTestRequest {

    /** 查询文本 */
    @NotBlank(message = "查询文本不能为空")
    @Size(max = 500, message = "查询文本不能超过 500 字")
    private String query;

    /** 返回条数（默认 5，1~20） */
    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 20, message = "topK 最大为 20")
    private Integer topK = 5;

    /** 可选：按知识层级过滤（BUILTIN/PRIVATE/CASE） */
    @Size(max = 16, message = "层级参数过长")
    private String layer;
}
