package com.example.opsaiagent.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 告警规则创建 / 更新请求
 */
@Data
public class AlertRuleRequest {

    @NotBlank(message = "规则名称不能为空")
    private String name;

    /** 租户 ID；平台创建时使用，企业用户可空 */
    private String tenantId;

    /** 归属部门；为空时使用当前部门 */
    private Long deptId;

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

    /** 升级策略：是否启用（规格 4.1，默认开启） */
    private Boolean escalationEnabled = false;

    /** 升级间隔（秒），兼容旧版默认 300（5 分钟） */
    private Integer escalationIntervalSeconds = 300;

    /** 最大升级级别，兼容旧版默认 2 */
    private Integer escalationMaxLevel = 2;

    /** 升级路径多级（规格 4.1：Level N · after · triggerOn · toRole · toUsers） */
    private List<AlertRuleLevelDto> escalationLevels;
}
