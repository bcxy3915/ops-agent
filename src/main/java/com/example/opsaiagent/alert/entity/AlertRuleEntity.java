package com.example.opsaiagent.alert.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则实体（alert_rule 表）
 */
@Data
@TableName("alert_rule")
public class AlertRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名称（唯一） */
    @TableField("name")
    private String name;

    /** 规则描述 */
    @TableField("description")
    private String description;

    /** 目标服务名（可为空 = 全部服务） */
    @TableField("service")
    private String service;

    /** 指标名：cpu / memory / threadCount / gcPause / hikariActive */
    @TableField("metric")
    private String metric;

    /** 比较符：gt / lt */
    @TableField("operator")
    private String operator;

    /** 阈值 */
    @TableField("threshold")
    private Double threshold;

    /** 比较模式：THRESHOLD / YOY / SIGMA */
    @TableField("compare_mode")
    private String compareMode;

    /** 持续 N 秒后才触发 */
    @TableField("duration_seconds")
    private Integer durationSeconds;

    /** 触发后静默期（秒），静默期内不重复告警 */
    @TableField("silence_seconds")
    private Integer silenceSeconds;

    /** 是否启用 */
    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
