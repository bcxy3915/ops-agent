package com.example.opsaiagent.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "登录请求")
public class LoginRequest {

    /**
     * 登录标识：新用户为邮箱；存量账号可继续使用用户名，或使用迁移生成的 username@local。
     * 字段名保留 username 是为了兼容现有前端，后端会优先按邮箱身份查找。
     */
    @Schema(description = "登录标识（邮箱；兼容旧用户名）", example = "zhangwei@xinghai.com",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "登录标识不能为空")
    private String username;

    @Schema(description = "密码", example = "admin123", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    private String password;
}