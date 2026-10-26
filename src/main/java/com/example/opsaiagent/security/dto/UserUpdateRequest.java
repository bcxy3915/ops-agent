package com.example.opsaiagent.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新用户请求（只更新非空字段；phone/email 传空串表示清除）
 */
@Data
@Schema(description = "更新用户请求")
public class UserUpdateRequest {

    @Schema(description = "显示名称（可选）")
    @Size(max = 50, message = "显示名称最多 50 字")
    private String displayName;

    @Schema(description = "历史角色", allowableValues = {"ADMIN", "OPERATOR", "VIEWER"})
    @Pattern(regexp = "ADMIN|OPERATOR|VIEWER", message = "角色必须为 ADMIN/OPERATOR/VIEWER")
    private String role;

    @Schema(description = "部门 ID（可选）")
    private Long deptId;

    @Schema(description = "新角色 ID 列表（可选；传空数组表示清空）")
    private List<Long> roleIds;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "新密码（可选，不传则不修改）")
    @Size(min = 6, max = 64, message = "密码长度 6-64 位")
    private String password;

    @Schema(description = "手机号（可选；传空串表示清除）")
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "邮箱（可选；传空串表示清除）")
    @Email(message = "邮箱格式不正确")
    private String email;
}
