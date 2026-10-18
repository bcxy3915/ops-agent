package com.example.opsaiagent.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 告警规则创建 / 更新请求
 */
@Data
public class AlertRuleRequest {

    @NotBlank(message = "规则名称不能为空")
    private String name;

    private String description;

    /** 目标服务名（可为空 = 全部服务） */
    private String service;

    @NotBlank(message = "指标不能为空")
    private String metric;

    /** gt / lt */
    @NotBlank(message = "比较符不能为空")
    private String operator;

    @NotNull(message = "阈值不能为空")
    private Double threshold;

    /** THRESHOLD / YOY / SIGMA */
    private String compareMode = "THRESHOLD";

    /** 持续 N 秒才触发 */
    private Integer durationSeconds = 60;

    /** 触发后静默期（秒） */
    private Integer silenceSeconds = 0;

    private Boolean enabled = true;
}
