package com.example.opsaiagent.security.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ops_user")
public class OpsUserEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String username;

    /**
     * 显示名称（displayName，规格 5.1）
     */
    @TableField("display_name")
    private String displayName;

    private String password;

    /**
     * 角色：ADMIN / OPERATOR / VIEWER（历史字段，新权限模型以 ops_user_role 为准）
     */
    private String role;

    /**
     * 所属租户 ID；平台管理员可为 0
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 所属部门 ID；平台管理员可为空
     */
    @TableField("dept_id")
    private Long deptId;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 加密后的手机号（AES-256-GCM）
     */
    private String phoneEnc;

    /**
     * 手机号哈希（SHA-256，供精确查询）
     */
    private String phoneHash;

    /**
     * 加密后的邮箱
     */
    private String emailEnc;

    /**
     * 邮箱哈希
     */
    private String emailHash;

    /**
     * 明文手机号（不映射 DB 字段，供业务层临时传递）
     */
    @TableField(exist = false)
    private String phone;

    /**
     * 明文邮箱（不映射 DB 字段，供业务层临时传递）
     */
    @TableField(exist = false)
    private String email;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}