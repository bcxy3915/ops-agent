package com.example.opsaiagent.rbac.context;

/**
 * 租户上下文。
 *
 * 由 JWT 认证过滤器在请求进入时写入，Service/DAO 层通过 {@link #get()} 获取当前租户，
 * 请求结束后必须调用 {@link #clear()}，避免线程池复用导致租户串号。
 *
 * 平台公共数据使用保留租户 {@code 0}，引导租户为 {@code t-default}。
 */
public final class TenantContext {

    /** 平台公共数据保留租户 ID */
    public static final String PLATFORM_TENANT_ID = "0";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 写入当前租户 ID。
     *
     * @param tenantId 租户 ID；传 null 表示清除
     */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(tenantId);
        }
    }

    /**
     * 获取当前租户 ID。
     *
     * @return 租户 ID；未设置时返回 null
     */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * 清除当前线程的租户上下文。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
