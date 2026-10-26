package com.example.opsaiagent.rbac.dto;

import java.util.Set;

/**
 * 用户授权信息快照。
 *
 * @param roles       角色编码集合
 * @param permissions 权限点编码集合
 */
public record UserAuthorities(Set<String> roles, Set<String> permissions) {

    public UserAuthorities {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /**
     * 空授权信息。
     *
     * @return 不含任何角色与权限的快照
     */
    public static UserAuthorities empty() {
        return new UserAuthorities(Set.of(), Set.of());
    }
}
