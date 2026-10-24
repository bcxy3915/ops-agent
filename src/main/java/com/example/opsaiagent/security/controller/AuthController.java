package com.example.opsaiagent.security.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.rbac.context.UserContext;
import com.example.opsaiagent.security.dto.LoginRequest;
import com.example.opsaiagent.security.dto.LoginResponse;
import com.example.opsaiagent.security.service.AuthService;
import com.example.opsaiagent.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 认证接口。
 *
 * 登录支持邮箱（新）与旧用户名；当前用户接口返回租户/部门/角色/权限。
 */
@Tag(name = "认证", description = "登录、当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录", description = "邮箱（兼容旧用户名）+ 密码登录，返回 JWT Token 与租户/角色信息")
    @RateLimit(key = "login", limit = 5, period = 60, dimension = RateLimit.Dimension.IP)
    @AuditLog(operation = "LOGIN", description = "用户登录")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @Operation(summary = "当前用户", description = "获取当前登录用户、租户、部门、角色与权限点")
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication authentication) {
        UserContext.CurrentUser user = UserContext.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", authentication.getName());
        data.put("userId", SecurityUtils.getCurrentUserId());
        data.put("tenantId", SecurityUtils.getCurrentTenantId());
        data.put("deptId", SecurityUtils.getCurrentDeptId());
        data.put("roles", user == null ? Set.of() : user.roles());
        data.put("permissions", user == null ? Set.of() : user.permissions());
        data.put("authorities", authentication.getAuthorities());
        return ApiResponse.success(data);
    }
}
