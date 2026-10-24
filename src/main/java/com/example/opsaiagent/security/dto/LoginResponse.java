package com.example.opsaiagent.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录响应")
public class LoginResponse {

    @Schema(description = "JWT Token")
    private String token;

    @Schema(description = "Token 类型", example = "Bearer")
    private String type;

    @Schema(description = "用户名", example = "admin")
    private String username;

    @Schema(description = "主角色（历史字段，兼容旧前端）", example = "ADMIN")
    private String role;

    @Schema(description = "租户 ID")
    private String tenantId;

    @Schema(description = "部门 ID")
    private Long deptId;

    @Schema(description = "角色编码列表")
    private List<String> roles;

    @Schema(description = "权限点编码列表")
    private List<String> permissions;

    @Schema(description = "是否需要绑定真实邮箱（存量迁移账号为 true）")
    private Boolean needBindEmail;

    @Schema(description = "过期时间（秒）", example = "7200")
    private Long expiresIn;
}