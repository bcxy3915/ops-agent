package com.example.opsaiagent.diagnosis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 智能诊断结果（T14）
 * 结构化输出：根因 / 证据 / 置信度 / 建议 / 摘要。
 * 通过 Spring AI ChatClient.entity() 配合 JSON Schema 约束解析。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResult {

    /** 根因判断（一句话说清故障最可能的原因） */
    private String rootCause;

    /** 支撑证据（来自快照中的指标 / 状态 / 审计等事实） */
    private List<String> evidence;

    /** 置信度：0-100 整数 */
    private Integer confidence;

    /** 处置建议（按优先级排序，可执行的动作） */
    private List<String> suggestions;

    /** 简要分析摘要（补充说明，可选） */
    private String summary;
}
