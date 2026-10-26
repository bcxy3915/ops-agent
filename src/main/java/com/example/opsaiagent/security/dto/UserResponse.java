package com.example.opsaiagent.security.dto;

import com.example.opsaiagent.sensitive.Sensitive;
import com.example.opsaiagent.sensitive.SensitiveType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户信息响应（敏感字段已脱敏）")
public class UserResponse {

    @Schema(description = "用户 ID", example = "u001")
    private String id;

    @Schema(description = "用户名", example = "admin")
    private String username;

    @Schema(description = "显示名称", example = "张三")
    private String displayName;

    @Schema(description = "租户 ID")
    private String tenantId;

    @Schema(description = "部门 ID")
    private Long deptId;

    @Schema(description = "角色编码列表")
    private List<String> roles;

    @Sensitive(value = SensitiveType.PASSWORD)
    @Schema(description = "密码（脱敏）", example = "******")
    private String password;

    @Sensitive(value = SensitiveType.PHONE)
    @Schema(description = "手机号（脱敏）", example = "138****5678")
    private String phone;

    @Sensitive(value = SensitiveType.EMAIL)
    @Schema(description = "邮箱（脱敏）", example = "a***@example.com")
    private String email;

    @Schema(description = "角色", example = "ADMIN")
    private String role;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}