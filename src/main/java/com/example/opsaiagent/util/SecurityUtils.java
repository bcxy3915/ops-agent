package com.example.opsaiagent.util;

import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.rbac.context.UserContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具类。
 *
 * 统一从 SecurityContext / 租户上下文 / 用户上下文读取当前登录信息，
 * 避免业务代码直接依赖 Spring Security 的细节。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户名。
     *
     * @return 用户名；未登录/匿名返回 null
     */
    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return null;
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID；未登录返回 null
     */
    public static String getCurrentUserId() {
        UserContext.CurrentUser user = UserContext.get();
        return user == null ? null : user.userId();
    }

    /**
     * 获取当前租户 ID。
     *
     * @return 租户 ID；未设置返回 null
     */
    public static String getCurrentTenantId() {
        return TenantContext.get();
    }

    /**
     * 获取当前部门 ID。
     *
     * @return 部门 ID；未设置返回 null
     */
    public static Long getCurrentDeptId() {
        return DeptContext.get();
    }

    /**
     * 判断当前用户是否拥有指定权限点。
     *
     * @param permission 权限点编码，如 knowledge:review
     * @return true=拥有
     */
    public static boolean hasPermission(String permission) {
        UserContext.CurrentUser user = UserContext.get();
        return user != null && permission != null && user.permissions().contains(permission);
    }
}
