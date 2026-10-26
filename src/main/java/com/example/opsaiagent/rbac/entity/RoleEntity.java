package com.example.opsaiagent.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体（ops_role）。
 *
 * 角色是岗位，权限点才是能力；用户可拥有多个角色，权限取并集。
 */
@Data
@TableName("ops_role")
public class RoleEntity {

    /** 角色 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，如 TENANT_ADMIN */
    private String code;

    /** 角色名称 */
    private String name;

    /** 作用域：PLATFORM / TENANT / DEPT */
    private String scope;

    /** 是否内置角色 */
    private Boolean builtin;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
