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

    private String password;

    /**
     * 角色：ADMIN / OPERATOR / VIEWER
     */
    private String role;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}