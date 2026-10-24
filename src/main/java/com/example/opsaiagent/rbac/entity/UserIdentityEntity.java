package com.example.opsaiagent.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录身份实体（ops_user_identity）。
 *
 * 用户与登录方式解耦：一个用户可以绑定邮箱密码、微信、OIDC 等多种方式。
 * 新增登录方式只需增加一行 identity，不影响用户/角色/权限模型。
 */
@Data
@TableName("ops_user_identity")
public class UserIdentityEntity {

    /** 身份 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 ops_user.id */
    private String userId;

    /** 登录方式：LOCAL / WECHAT / OIDC / SSO */
    private String provider;

    /** 登录标识：LOCAL 为邮箱，第三方为 openid/sub */
    private String identifier;

    /** LOCAL 密码 BCrypt；第三方为空 */
    private String credentialHash;

    /** 扩展信息（头像、unionid 等） */
    private String extraJson;

    /** 存量迁移账号为 TRUE，首次登录后应绑定真实邮箱 */
    private Boolean needBindEmail;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
