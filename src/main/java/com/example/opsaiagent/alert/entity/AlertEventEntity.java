package com.example.opsaiagent.alert.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警事件实体（alert_event 表）
 * 状态机：FIRING（触发中）→ RESOLVED（已恢复）/ ACKNOWLEDGED（已确认）
 */
@Data
@TableName("alert_event")
public class AlertEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联规则 ID */
    @TableField("rule_id")
    private Long ruleId;

    /** 规则名称（快照，避免规则删除后事件失去语义） */
    @TableField("rule_name")
    private String ruleName;

    /** 服务名 */
    @TableField("service")
    private String service;

    /** 指标名 */
    @TableField("metric")
    private String metric;

    /** 比较符 */
    @TableField("operator")
    private String operator;

    /** 触发阈值 */
    @TableField("threshold")
    private Double threshold;

    /** 触发时实际值 */
    @TableField("current_value")
    private Double currentValue;

    /** 状态：FIRING / RESOLVED / SILENCED / ACKNOWLEDGED */
    @TableField("status")
    private String status;

    /** 告警详情 */
    @TableField("detail")
    private String detail;

    /** 触发时间 */
    @TableField("fired_at")
    private LocalDateTime firedAt;

    /** 恢复时间 */
    @TableField("resolved_at")
    private LocalDateTime resolvedAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
