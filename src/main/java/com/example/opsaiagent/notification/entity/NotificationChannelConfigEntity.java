package com.example.opsaiagent.notification.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知渠道配置（notification_channel 表，T11）
 * config 为 JSON 字符串：
 *   FEISHU_WEBHOOK  -> {"webhookUrl":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx"}
 *   EMAIL           -> {"recipients":"a@x.com,b@x.com","subjectPrefix":"[Ops] "}
 *   GENERIC_WEBHOOK -> {"webhookUrl":"https://example.com/hook","secret":"可选签名"}
 */
@Data
@TableName("notification_channel")
public class NotificationChannelConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道名称（租户内唯一） */
    @TableField("name")
    private String name;

    /** 所属租户 */
    @TableField("tenant_id")
    private String tenantId;

    /** 归属部门 */
    @TableField("dept_id")
    private Long deptId;

    /** 渠道类型：FEISHU_WEBHOOK / EMAIL / GENERIC_WEBHOOK */
    @TableField("type")
    private String type;

    /** 是否启用 */
    @TableField("enabled")
    private Boolean enabled;

    /** 渠道参数（JSON） */
    @TableField("config")
    private String config;

    /** 描述 */
    @TableField("description")
    private String description;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
