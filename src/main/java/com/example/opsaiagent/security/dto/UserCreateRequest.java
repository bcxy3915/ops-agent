package com.example.opsaiagent.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "创建用户请求")
public class UserCreateRequest {

    @Schema(description = "用户名", example = "张三", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "[a-z0-9-]+", message = "只能包含小写字母、数字、连字符")
    @Size(min = 2, max = 64, message = "长度 2-64 位")
    private String username;

    @Schema(description = "显示名称", example = "张三")
    @Size(max = 50, message = "显示名称最多 50 字")
    private String displayName;

    @Schema(description = "密码（明文）", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度 6-64 位")
    private String password;

    @Schema(description = "历史角色；未传 roleIds 时用于映射新角色",
            allowableValues = {"ADMIN", "OPERATOR", "VIEWER"})
    @Pattern(regexp = "ADMIN|OPERATOR|VIEWER", message = "角色必须为 ADMIN/OPERATOR/VIEWER")
    private String role;

    @Schema(description = "租户 ID；平台管理员创建时使用，企业用户可空")
    private String tenantId;

    @Schema(description = "部门 ID；为空时使用当前登录用户部门")
    private Long deptId;

    @Schema(description = "新角色 ID 列表；优先于 role")
    private List<Long> roleIds;

    @Schema(description = "手机号", example = "13812345678")
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "邮箱", example = "zhangsan@example.com")
    @Email(message = "邮箱格式不正确")
    private String email;
}