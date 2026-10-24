package com.example.opsaiagent.rbac.context;

import java.util.Set;

/**
 * 当前登录用户上下文。
 *
 * 保存用户 ID、用户名、角色编码与权限点编码，供 Service 层做权限判断。
 * 由 JWT 认证过滤器写入，请求结束后必须调用 {@link #clear()}。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 写入当前登录用户。
     *
     * @param user 当前用户；传 null 表示清除
     */
    public static void set(CurrentUser user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }

    /**
     * 获取当前登录用户。
     *
     * @return 当前用户；未登录时返回 null
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 清除当前线程的用户上下文。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前登录用户的只读快照。
     *
     * @param userId      用户 ID
     * @param username    用户名
     * @param roles       角色编码集合（如 TENANT_ADMIN）
     * @param permissions 权限点集合（如 knowledge:review）
     */
    public record CurrentUser(String userId, String username, Set<String> roles, Set<String> permissions) {

        public CurrentUser {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
}
