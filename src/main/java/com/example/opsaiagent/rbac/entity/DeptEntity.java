package com.example.opsaiagent.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门实体（ops_dept）。
 *
 * 支持多级部门；{@code path} 为物化路径（如 {@code /1/5/}），
 * 用于“上级部门可见全部下级部门”的子树查询。
 */
@Data
@TableName("ops_dept")
public class DeptEntity {

    /** 部门 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属租户 */
    private String tenantId;

    /** 父部门 ID；根部门为空 */
    private Long parentId;

    /** 租户内唯一编码 */
    private String code;

    /** 部门名称 */
    private String name;

    /** 物化路径：/父ID/子ID/ */
    private String path;

    /** 层级，根部门为 1 */
    private Integer level;

    /** 同级排序 */
    private Integer sort;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
