package com.example.opsaiagent.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警升级事件记录（alert_escalation 表）
 * 规格 4.4：升级写表 + 审计；详情页处置时间线数据源
 */
@Data
@TableName("alert_escalation")
public class AlertEscalationRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 告警事件 ID */
    @TableField("alert_event_id")
    private Long alertEventId;

    /** 所属租户 */
    @TableField("tenant_id")
    private String tenantId;

    /** 归属部门 */
    @TableField("dept_id")
    private Long deptId;

    /** 升级到的级别 */
    @TableField("level")
    private Integer level;

    /** 触发条件：UNACKNOWLEDGED / UNRESOLVED */
    @TableField("trigger_on")
    private String triggerOn;

    /** 通知角色 */
    @TableField("to_role")
    private String toRole;

    /** 通知用户（逗号分隔） */
    @TableField("to_users")
    private String toUsers;

    /** 升级原因 */
    @TableField("reason")
    private String reason;

    /** 触发时间 */
    @TableField("triggered_at")
    private LocalDateTime triggeredAt;
}
