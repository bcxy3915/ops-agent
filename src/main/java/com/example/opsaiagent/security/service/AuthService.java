package com.example.opsaiagent.security.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.dto.UserAuthorities;
import com.example.opsaiagent.rbac.entity.UserIdentityEntity;
import com.example.opsaiagent.rbac.service.PermissionService;
import com.example.opsaiagent.rbac.service.TenantService;
import com.example.opsaiagent.rbac.service.UserIdentityService;
import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.security.dto.LoginRequest;
import com.example.opsaiagent.security.dto.LoginResponse;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 认证服务。
 *
 * 登录标识优先按 {@code ops_user_identity(provider=LOCAL)} 查找（邮箱登录），
 * 找不到时回退到旧 {@code ops_user.username}，保证存量账号平滑迁移。
 *
 * 登录成功后签发包含 userId / tenantId / deptId / roles 的 JWT；
 * 权限点不放入 JWT，由认证过滤器按 userId 从数据库缓存加载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OpsUserService userService;
    private final UserIdentityService userIdentityService;
    private final TenantService tenantService;
    private final PermissionService permissionService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 登录。
     *
     * @param request 登录请求（username 字段承载邮箱或旧用户名）
     * @return 登录响应（Token + 租户/部门/角色/权限）
     */
    public LoginResponse login(LoginRequest request) {
        String identifier = request.getUsername() == null ? "" : request.getUsername().trim();
        ResolvedUser resolved = resolveUser(identifier);
        OpsUserEntity user = resolved.user();

        if (resolved.credentialHash() == null
                || !passwordEncoder.matches(request.getPassword(), resolved.credentialHash())) {
            log.warn("登录失败: identifier={}, 密码错误", identifier);
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "用户名或密码错误");
        }

        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "账号已禁用");
        }

        // 租户停用后禁止该企业所有用户登录；旧账号 tenantId 为空时跳过
        if (user.getTenantId() != null && !tenantService.isActive(user.getTenantId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "所属企业已停用");
        }

        // 登录接口没有 JWT 过滤器写入上下文，这里补写，供审计日志记录正确的租户/部门
        TenantContext.set(user.getTenantId());
        DeptContext.set(user.getDeptId());

        UserAuthorities authorities = user.getId() == null
                ? UserAuthorities.empty()
                : permissionService.loadAuthorities(user.getId());
        if (authorities == null) {
            authorities = UserAuthorities.empty();
        }

        List<String> roles = new ArrayList<>(authorities.roles());
        // 兼容改造期旧注解 hasRole('ADMIN'/'OPERATOR'/'VIEWER')：
        // 在保留新角色（PLATFORM_ADMIN/TENANT_ADMIN/...）的同时，附加历史角色字段
        if (user.getRole() != null && !user.getRole().isBlank() && !roles.contains(user.getRole())) {
            roles.add(user.getRole());
        }
        List<String> permissions = new ArrayList<>(authorities.permissions());

        String token = jwtService.generateToken(
                user.getUsername(),
                user.getRole(),
                user.getId(),
                user.getTenantId(),
                user.getDeptId(),
                roles);
        log.info("登录成功: username={}, tenantId={}, roles={}", user.getUsername(), user.getTenantId(), roles);

        return LoginResponse.builder()
                .token(token)
                .type("Bearer")
                .username(user.getUsername())
                .role(user.getRole())
                .tenantId(user.getTenantId())
                .deptId(user.getDeptId())
                .roles(roles)
                .permissions(permissions)
                .needBindEmail(resolved.needBindEmail())
                .expiresIn(jwtService.getExpirationSeconds())
                .build();
    }

    /**
     * 解析登录用户：优先邮箱身份，回退旧用户名。
     *
     * @param identifier 邮箱或旧用户名
     * @return 用户 + 凭证哈希 + 是否需要绑定邮箱
     */
    private ResolvedUser resolveUser(String identifier) {
        Optional<UserIdentityEntity> identity = userIdentityService.findLocal(identifier);
        if (identity.isPresent()) {
            UserIdentityEntity row = identity.get();
            if (!"ACTIVE".equalsIgnoreCase(row.getStatus())) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "登录方式已停用");
            }
            OpsUserEntity user = userService.findById(row.getUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAMETER, "用户名或密码错误"));
            return new ResolvedUser(user, row.getCredentialHash(),
                    Boolean.TRUE.equals(row.getNeedBindEmail()));
        }

        // 兼容旧用户名登录；迁移后的用户首次登录应引导绑定真实邮箱
        OpsUserEntity user = userService.findByUsername(identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAMETER, "用户名或密码错误"));
        return new ResolvedUser(user, user.getPassword(), true);
    }

    /**
     * 登录用户解析结果。
     *
     * @param user          用户实体
     * @param credentialHash 用于校验的密码哈希
     * @param needBindEmail 是否需要绑定真实邮箱
     */
    private record ResolvedUser(OpsUserEntity user, String credentialHash, boolean needBindEmail) {
    }
}
