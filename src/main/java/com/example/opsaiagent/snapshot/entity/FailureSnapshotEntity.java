package com.example.opsaiagent.snapshot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 故障快照（failure_snapshot 表，T13）
 * 告警触发时聚合服务状态 / 指标窗口 / 服务信息 / 审计日志，以结构化 JSON 落库。
 */
@Data
@TableName("failure_snapshot")
public class FailureSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联告警事件 ID */
    @TableField("alert_event_id")
    private Long alertEventId;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("rule_name")
    private String ruleName;

    @TableField("service")
    private String service;

    @TableField("metric")
    private String metric;

    /** 告警状态：FIRING / RESOLVED / SILENCED / ACKNOWLEDGED */
    @TableField("status")
    private String status;

    /** 结构化 JSON 快照 */
    @TableField("snapshot_json")
    private String snapshotJson;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
