package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 故障案例沉淀实体（knowledge_case 表）
 * 从诊断记录自动生成候选案例，经人工确认/修正后沉淀为知识。
 * status: PENDING(待确认) / CONFIRMED(已沉淀) / DISCARDED(已丢弃)
 */
@Data
@TableName("knowledge_case")
public class KnowledgeCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源告警事件 ID */
    @TableField("source_alert_id")
    private Long sourceAlertId;

    /** 来源诊断记录 ID */
    @TableField("diagnosis_record_id")
    private Long diagnosisRecordId;

    /** 涉及服务 */
    @TableField("service")
    private String service;

    /** 案例标题 */
    @TableField("title")
    private String title;

    /** 故障现象 */
    @TableField("symptom")
    private String symptom;

    /** AI 判断根因 */
    @TableField("root_cause")
    private String rootCause;

    /** 人工确认/修正后的真实根因 */
    @TableField("real_root_cause")
    private String realRootCause;

    /** 证据列表（JSON 数组字符串） */
    @TableField("evidence")
    private String evidence;

    /** 有效处置动作 */
    @TableField("effective_action")
    private String effectiveAction;

    /** 置信度 0-100 */
    @TableField("confidence")
    private Integer confidence;

    /** 脱敏状态：NONE / PARTIAL / FULL */
    @TableField("masked_status")
    private String maskedStatus;

    /** 案例状态：PENDING / CONFIRMED / DISCARDED */
    @TableField("status")
    private String status;

    /** 故障持续时长（分钟） */
    @TableField("duration_min")
    private Integer durationMin;

    /** 闭环时间 */
    @TableField("closed_loop_at")
    private LocalDateTime closedLoopAt;

    /** 确认人 */
    @TableField("confirmed_by")
    private String confirmedBy;

    /** 被引用次数 */
    @TableField("cited_count")
    private Integer citedCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
