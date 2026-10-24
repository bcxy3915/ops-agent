package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.rbac.dto.UserAuthorities;
import com.example.opsaiagent.rbac.entity.PermissionEntity;
import com.example.opsaiagent.rbac.entity.RoleEntity;
import com.example.opsaiagent.rbac.mapper.PermissionMapper;
import com.example.opsaiagent.rbac.mapper.RoleMapper;
import com.example.opsaiagent.rbac.mapper.RolePermissionMapper;
import com.example.opsaiagent.rbac.mapper.UserRoleMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限服务。
 *
 * 按用户加载“角色 + 权限点”，权限点用于 {@code @PreAuthorize}，
 * 角色同时以 {@code ROLE_} 前缀写入权限集合，兼容改造期的旧注解。
 *
 * 使用 Caffeine 缓存 5 分钟；角色/权限变更时调用 {@link #evict(String)} 失效。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    /** 用户授权缓存：key=userId，TTL=5 分钟 */
    private final Cache<String, UserAuthorities> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /**
     * 加载用户授权信息（角色 + 权限点）。
     *
     * @param userId 用户 ID
     * @return 授权快照；用户为空或无角色时返回空授权
     */
    public UserAuthorities loadAuthorities(String userId) {
        if (userId == null || userId.isBlank()) {
            return UserAuthorities.empty();
        }
        return cache.get(userId, this::loadFromDb);
    }

    /**
     * 获取用户权限点编码。
     *
     * @param userId 用户 ID
     * @return 权限点集合
     */
    public Set<String> getPermissions(String userId) {
        return loadAuthorities(userId).permissions();
    }

    /**
     * 获取用户角色编码。
     *
     * @param userId 用户 ID
     * @return 角色集合
     */
    public Set<String> getRoles(String userId) {
        return loadAuthorities(userId).roles();
    }

    /**
     * 失效指定用户的授权缓存。
     *
     * @param userId 用户 ID
     */
    public void evict(String userId) {
        if (userId != null) {
            cache.invalidate(userId);
        }
    }

    /**
     * 从数据库加载用户授权。
     *
     * @param userId 用户 ID
     * @return 授权快照
     */
    private UserAuthorities loadFromDb(String userId) {
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return UserAuthorities.empty();
        }

        List<RoleEntity> roles = roleMapper.selectBatchIds(roleIds);
        Set<String> roleCodes = new LinkedHashSet<>();
        for (RoleEntity role : roles) {
            if (role != null && role.getCode() != null && !role.getCode().isBlank()) {
                roleCodes.add(role.getCode());
            }
        }

        List<Long> permissionIds = rolePermissionMapper.selectPermissionIdsByRoleIds(roleIds);
        if (permissionIds == null || permissionIds.isEmpty()) {
            return new UserAuthorities(roleCodes, Set.of());
        }

        List<PermissionEntity> permissions = permissionMapper.selectBatchIds(permissionIds);
        Set<String> permissionCodes = new LinkedHashSet<>();
        for (PermissionEntity permission : permissions) {
            if (permission != null && permission.getCode() != null && !permission.getCode().isBlank()) {
                permissionCodes.add(permission.getCode());
            }
        }
        return new UserAuthorities(roleCodes, permissionCodes);
    }
}
