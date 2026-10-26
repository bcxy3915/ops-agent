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

    /** 所属租户 */
    @TableField("tenant_id")
    private String tenantId;

    /** 归属部门 */
    @TableField("dept_id")
    private Long deptId;

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

    /** 认领/确认人（acknowledge 时记录当前登录用户） */
    @TableField("acknowledged_by")
    private String acknowledgedBy;

    /** 认领/确认时间 */
    @TableField("acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /** 恢复操作人 */
    @TableField("resolved_by")
    private String resolvedBy;

    /** 恢复备注（resolutionNote，规格 2.4，最多 500 字） */
    @TableField("resolution_note")
    private String resolutionNote;

    /** 手动静默到期时间（SILENCED 状态时生效） */
    @TableField("silenced_until")
    private LocalDateTime silencedUntil;

    /** 静默原因 */
    @TableField("silenced_reason")
    private String silencedReason;

    /** 静默操作人 */
    @TableField("silenced_by")
    private String silencedBy;

    /** 静默前状态（到期/解除后回原状态，规格 2.5） */
    @TableField("silenced_from")
    private String silencedFrom;

    /** 静默备注（规格 2.5 note，可选） */
    @TableField("silenced_note")
    private String silencedNote;

    /** 静默操作时间（规格 3.5 处置时间线，首次静默时记录） */
    @TableField("silenced_at")
    private LocalDateTime silencedAt;

    /** 升级级别（0=未升级，1..N） */
    @TableField("escalation_level")
    private Integer escalationLevel;

    /** 最近一次升级时间 */
    @TableField("escalated_at")
    private LocalDateTime escalatedAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
