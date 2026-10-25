package com.example.opsaiagent.rbac.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.Callable;

/**
 * 租户 / 部门 / 用户上下文快照。
 *
 * 用于异步线程、定时任务与 SSE 流：提交线程捕获上下文，执行线程恢复，结束后清理。
 * 避免线程池复用导致租户串号。
 */
public final class RbacContextSnapshot {

    private final String tenantId;
    private final Long deptId;
    private final UserContext.CurrentUser user;
    private final Authentication authentication;

    private RbacContextSnapshot(String tenantId, Long deptId,
                                UserContext.CurrentUser user, Authentication authentication) {
        this.tenantId = tenantId;
        this.deptId = deptId;
        this.user = user;
        this.authentication = authentication;
    }

    /**
     * 捕获当前线程上下文。
     *
     * @return 上下文快照
     */
    public static RbacContextSnapshot capture() {
        return new RbacContextSnapshot(
                TenantContext.get(),
                DeptContext.get(),
                UserContext.get(),
                SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 构造仅含租户的系统上下文快照。
     *
     * @param tenantId 租户 ID
     * @return 上下文快照
     */
    public static RbacContextSnapshot forTenant(String tenantId) {
        return new RbacContextSnapshot(tenantId, null, null, null);
    }

    /**
     * 在恢复的上下文中执行有返回值任务。
     *
     * @param task 任务
     * @param <T>  返回类型
     * @return 任务结果
     * @throws Exception 任务异常
     */
    public <T> T call(Callable<T> task) throws Exception {
        restore();
        try {
            return task.call();
        } finally {
            clear();
        }
    }

    /**
     * 在恢复的上下文中执行无返回值任务。
     *
     * @param task 任务
     */
    public void run(Runnable task) {
        restore();
        try {
            task.run();
        } finally {
            clear();
        }
    }

    /**
     * 包装 Runnable：执行前恢复上下文，执行后清理。
     *
     * @param task 原任务
     * @return 包装后任务
     */
    public Runnable wrap(Runnable task) {
        return () -> run(task);
    }

    /**
     * 包装 Callable：执行前恢复上下文，执行后清理。
     *
     * @param task 原任务
     * @param <T>  返回类型
     * @return 包装后任务
     */
    public <T> Callable<T> wrap(Callable<T> task) {
        return () -> call(task);
    }

    /**
     * 恢复上下文到当前线程。
     */
    public void restore() {
        TenantContext.set(tenantId);
        DeptContext.set(deptId);
        UserContext.set(user);
        if (authentication == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }

    /**
     * 清理当前线程上下文。
     */
    public void clear() {
        TenantContext.clear();
        DeptContext.clear();
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }
}
