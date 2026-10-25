package com.example.opsaiagent.notification.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知发送记录（notification_record 表，T10）
 * 状态机：PENDING（待发）→ SUCCESS（成功）/ FAILED（失败，按策略重试）
 */
@Data
@TableName("notification_record")
public class NotificationRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联告警事件 ID（可为空：测试发送） */
    @TableField("alert_event_id")
    private Long alertEventId;

    /** 所属租户 */
    @TableField("tenant_id")
    private String tenantId;

    /** 归属部门 */
    @TableField("dept_id")
    private Long deptId;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("rule_name")
    private String ruleName;

    @TableField("service")
    private String service;

    @TableField("metric")
    private String metric;

    /** 渠道 ID */
    @TableField("channel_id")
    private Long channelId;

    /** 渠道名称（快照） */
    @TableField("channel_name")
    private String channelName;

    /** 渠道类型 */
    @TableField("channel_type")
    private String channelType;

    /** 发送目标（webhook URL / 收件人列表） */
    @TableField("target")
    private String target;

    /** 标题 */
    @TableField("title")
    private String title;

    /** 正文 */
    @TableField("content")
    private String content;

    /** 状态：PENDING / SUCCESS / FAILED */
    @TableField("status")
    private String status;

    /** 失败原因 */
    @TableField("error_message")
    private String errorMessage;

    /** 已重试次数 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 最大重试次数 */
    @TableField("max_retry")
    private Integer maxRetry;

    /** 下次重试时间 */
    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    /** 实际发送时间 */
    @TableField("sent_at")
    private LocalDateTime sentAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
