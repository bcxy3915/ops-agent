package com.example.opsaiagent.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维护窗口（maintenance_window 表，P1 规格 5）
 * 服务维度：存量告警置静默、维护期内新告警落库但不通知不升级、到期自动解除。
 * ended_at 非空表示已提前结束；until 为计划到期时间。
 */
@Data
@TableName("maintenance_window")
public class MaintenanceWindowEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 维护目标服务 */
    @TableField("service")
    private String service;

    /** 所属租户 */
    @TableField("tenant_id")
    private String tenantId;

    /** 归属部门 */
    @TableField("dept_id")
    private Long deptId;

    /** 维护原因（与单条静默原因口径一致） */
    @TableField("reason")
    private String reason;

    /** 计划到期时间（维护窗口结束） */
    @TableField("until")
    private LocalDateTime until;

    /** 备注 */
    @TableField("note")
    private String note;

    /** 创建人（当前登录用户名） */
    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 实际结束时间；非空表示窗口已结束（提前结束或到期扫描置位） */
    @TableField("ended_at")
    private LocalDateTime endedAt;
}
