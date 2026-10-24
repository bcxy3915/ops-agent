package com.example.opsaiagent.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业/租户实体（ops_tenant）。
 *
 * 多租户数据隔离的根；平台公共数据使用保留租户 {@code 0}。
 */
@Data
@TableName("ops_tenant")
public class TenantEntity {

    /** 租户 ID；平台公共数据保留 0 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户编码（全局唯一） */
    private String code;

    /** 企业名称 */
    private String name;

    /** 状态：ACTIVE / SUSPENDED */
    private String status;

    /** 知识审核模式：DEPT_ONLY / DEPT_AND_TENANT / NONE */
    @TableField("knowledge_review_mode")
    private String knowledgeReviewMode;

    /** 四眼原则开关：TRUE 时审核人不能是上传人 */
    @TableField("review_four_eyes")
    private Boolean reviewFourEyes;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
