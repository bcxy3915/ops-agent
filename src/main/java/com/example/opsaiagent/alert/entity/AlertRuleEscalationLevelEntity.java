package com.example.opsaiagent.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则升级路径（alert_rule_escalation_level 表）
 * 规格 4.1：Level N · after 时间 · triggerOn · toRole · toUsers · channelIds
 */
@Data
@TableName("alert_rule_escalation_level")
public class AlertRuleEscalationLevelEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属规则 ID */
    @TableField("rule_id")
    private Long ruleId;

    /** 所属租户 */
    @TableField("tenant_id")
    private String tenantId;

    /** 级别（1..N，同一规则内唯一） */
    @TableField("level_no")
    private Integer levelNo;

    /** 触发条件计时秒数（after） */
    @TableField("after_seconds")
    private Integer afterSeconds;

    /** 触发条件：UNACKNOWLEDGED（未确认）/ UNRESOLVED（未恢复） */
    @TableField("trigger_on")
    private String triggerOn;

    /** 通知角色：OPERATOR / ADMIN */
    @TableField("to_role")
    private String toRole;

    /** 指定用户（逗号分隔用户名，规格 toUsers[]） */
    @TableField("to_users")
    private String toUsers;

    /** 指定渠道 ID（逗号分隔，默认继承规则渠道） */
    @TableField("channel_ids")
    private String channelIds;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
