package com.example.opsaiagent.rbac.context;

/**
 * 部门上下文。
 *
 * 由 JWT 认证过滤器在请求进入时写入，用于计算当前用户可见的部门子树。
 * 请求结束后必须调用 {@link #clear()}。
 */
public final class DeptContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private DeptContext() {
    }

    /**
     * 写入当前部门 ID。
     *
     * @param deptId 部门 ID；传 null 表示清除
     */
    public static void set(Long deptId) {
        if (deptId == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(deptId);
        }
    }

    /**
     * 获取当前部门 ID。
     *
     * @return 部门 ID；未设置时返回 null
     */
    public static Long get() {
        return HOLDER.get();
    }

    /**
     * 清除当前线程的部门上下文。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
