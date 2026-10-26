package com.example.opsaiagent.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 权限点实体（ops_permission）。
 *
 * 代码统一使用 {@code @PreAuthorize("hasAuthority('knowledge:review')")}
 * 这类权限点判断，不再写死角色。
 */
@Data
@TableName("ops_permission")
public class PermissionEntity {

    /** 权限 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限编码，如 knowledge:review */
    private String code;

    /** 权限名称 */
    private String name;

    /** 所属模块 */
    private String module;
}
