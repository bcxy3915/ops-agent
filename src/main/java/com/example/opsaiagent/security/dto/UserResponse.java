package com.example.opsaiagent.security.dto;

import com.example.opsaiagent.sensitive.Sensitive;
import com.example.opsaiagent.sensitive.SensitiveType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "用户信息响应（敏感字段已脱敏）")
public class UserResponse {

    @Schema(description = "用户 ID", example = "u001")
    private String id;

    @Schema(description = "用户名", example = "admin")
    private String username;

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