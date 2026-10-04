package com.example.opsaiagent.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Schema(description = "角色", example = "ADMIN")
    private String role;

    @Schema(description = "过期时间（秒）", example = "7200")
    private Long expiresIn;
}