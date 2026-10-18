package com.example.opsaiagent.knowledge.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 案例修正沉淀请求（T21）
 * 修正后确认沉淀：可修正真实根因 / 有效处置动作 / 脱敏状态。
 */
@Data
public class CaseReviseRequest {

    /** 人工确认后的真实根因（可选，不传则沿用 AI 根因） */
    @Size(max = 2000, message = "真实根因长度不能超过 2000")
    private String realRootCause;

    /** 有效处置动作（可选） */
    @Size(max = 2000, message = "有效处置动作长度不能超过 2000")
    private String effectiveAction;

    /** 脱敏状态：NONE / PARTIAL / FULL（可选，默认 NONE） */
    private String maskedStatus;
}
