package com.example.opsaiagent.security.filter;

import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.rbac.context.UserContext;
import com.example.opsaiagent.rbac.dto.UserAuthorities;
import com.example.opsaiagent.rbac.service.PermissionService;
import com.example.opsaiagent.security.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * JWT 认证过滤器。
 *
 * 解析 Token 后同时完成四件事：
 *  写入 SecurityContext（用户名 + 角色 + 权限点）；
 *  写入 {@link TenantContext} 与 {@link DeptContext}；
 *  写入 {@link UserContext}（userId / 角色 / 权限点）；
 *  请求结束后清理 ThreadLocal，避免线程池复用串租户。
 *
 * Token 中的权限点不在 JWT 内，而是按 userId 从 {@link PermissionService}
 * 加载（带缓存）；旧 Token 无 userId 时仅保留角色权限，兼容改造期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final PermissionService permissionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 进入请求先清理，防止上一次请求的上下文残留
        clearContexts();

        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    if (jwtService.isValid(token)) {
                        authenticate(token, request);
                    }
                } catch (Exception e) {
                    log.warn("Token 解析失败: {}", e.getMessage());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            clearContexts();
        }
    }

    /**
     * 解析 Token 并写入认证上下文。
     *
     * @param token   JWT
     * @param request 当前请求，用于 WebAuthenticationDetails
     */
    private void authenticate(String token, HttpServletRequest request) {
        Claims claims = jwtService.parseToken(token);
        String username = claims.getSubject();
        String role = claims.get("role", String.class);
        String userId = claims.get("userId", String.class);
        String tenantId = claims.get("tenantId", String.class);
        Long deptId = parseLong(claims.get("deptId"));

        List<String> roles = parseRoles(claims);
        if (roles.isEmpty() && role != null && !role.isBlank()) {
            roles = List.of(role);
        }

        TenantContext.set(tenantId);
        DeptContext.set(deptId);

        Set<String> permissions = Set.of();
        Set<String> effectiveRoles = new LinkedHashSet<>(roles);
        if (userId != null && !userId.isBlank()) {
            UserAuthorities authorities = permissionService.loadAuthorities(userId);
            permissions = authorities.permissions();
            // 兼容：JWT 角色缺失时以数据库角色为准；JWT 角色存在时以 JWT 为准（避免频繁查库）
            if (effectiveRoles.isEmpty()) {
                effectiveRoles.addAll(authorities.roles());
            }
        }

        Set<SimpleGrantedAuthority> granted = new LinkedHashSet<>();
        for (String r : effectiveRoles) {
            if (r != null && !r.isBlank()) {
                granted.add(new SimpleGrantedAuthority("ROLE_" + r));
            }
        }
        for (String p : permissions) {
            if (p != null && !p.isBlank()) {
                granted.add(new SimpleGrantedAuthority(p));
            }
        }

        UserContext.set(new UserContext.CurrentUser(userId, username, effectiveRoles, permissions));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(username, null, granted);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 解析 roles claim（多角色）。
     *
     * @param claims JWT claims
     * @return 角色编码列表；缺失时返回空列表
     */
    private List<String> parseRoles(Claims claims) {
        Object raw = claims.get("roles");
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    /**
     * 解析数字型 claim（JWT 反序列化可能为 Integer/Long/String）。
     *
     * @param value claim 值
     * @return Long；无法解析时返回 null
     */
    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                // 非法数字按缺失处理
            }
        }
        return null;
    }

    /**
     * 清理请求级上下文，避免线程池复用导致租户/部门串号。
     */
    private void clearContexts() {
        TenantContext.clear();
        DeptContext.clear();
        UserContext.clear();
    }
}
