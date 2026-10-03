package com.example.opsaiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务实体
 */
@Data
@TableName("ops_service")
public class OpsServiceEntity {

    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 名称
     */
    @TableField("name")
    private String name;

    /**
     * 基础 URL
     */
    @TableField("base_url")
    private String baseUrl;

    /**
     * 健康检查路径
     */
    @TableField("health_path")
    private String healthPath;

    /**
     * 指标路径
     */
    @TableField("metrics_path")
    private String metricsPath;

    /**
     * 所有者
     */
    @TableField("owner")
    private String owner;

    /**
     * 环境
     */
    @TableField("env")
    private String env;

    /**
     * 标签
     * JSONB 类型，简单起见用 String 存
     */
    @TableField("tags")
    private String tags;

    /**
     * 认证令牌
     */
    @TableField("auth_token")
    private String authToken;

    /**
     * 状态
     */
    @TableField("status")
    private String status;

    /**
     * 最后检查时间
     */
    @TableField("last_checked_at")
    private LocalDateTime lastCheckedAt;

    /**
     * 注册时间
     */
    @TableField(value = "registered_at", fill = FieldFill.INSERT)
    private LocalDateTime registeredAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}