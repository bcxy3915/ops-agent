package com.example.opsaiagent.alert.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户告警已读记录（user_alert_read 表）
 * 语义：已读 ≠ 已处理（ACK）。已读只影响铃铛未读计数，ACK 是独立处置动作。
 */
@Data
@TableName("user_alert_read")
public class AlertReadEntity {

    /** 登录用户名 */
    @TableField("user_id")
    private String userId;

    /** 告警事件 ID */
    @TableField("alert_event_id")
    private Long alertEventId;

    /** 已读时间 */
    @TableField("read_at")
    private LocalDateTime readAt;
}
