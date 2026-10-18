package com.example.opsaiagent.diagnosis.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 智能诊断记录（diagnosis_record 表，T15）
 * 一次告警诊断 = 快照 → LLM → 结构化诊断结果落库；支持基于诊断的追问。
 */
@Data
@TableName("diagnosis_record")
public class DiagnosisRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联告警事件 ID */
    @TableField("alert_event_id")
    private Long alertEventId;

    /** 关联故障快照 ID */
    @TableField("snapshot_id")
    private Long snapshotId;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("rule_name")
    private String ruleName;

    @TableField("service")
    private String service;

    @TableField("metric")
    private String metric;

    /** 诊断状态：SUCCESS / FAILED */
    @TableField("status")
    private String status;

    /** 根因判断 */
    @TableField("root_cause")
    private String rootCause;

    /** 证据列表（JSON 数组） */
    @TableField("evidence")
    private String evidence;

    /** 置信度 0-100 */
    @TableField("confidence")
    private Integer confidence;

    /** 处置建议（JSON 数组） */
    @TableField("suggestions")
    private String suggestions;

    /** 诊断摘要 */
    @TableField("summary")
    private String summary;

    /** 追问问题（主诊断为空，追问时非空） */
    @TableField("question")
    private String question;

    /** 追问回答（追问时填充） */
    @TableField("answer")
    private String answer;

    /** 失败原因 */
    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
