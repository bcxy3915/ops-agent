package com.example.opsaiagent.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP 地址工具类
 * 职责：
 * 1. 从请求头或远程地址中提取客户端真实 IP
 * 2. 规范化 IP 格式（IPv6 localhost → 127.0.0.1 等）
 * 被 AuditLogAspect 和 RateLimitAspect 共用。
 */
public final class IpUtils {

    /** 常见的代理转发 header，按优先级排列 */
    private static final String[] PROXY_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP"
    };

    private IpUtils() {
        // 工具类禁止实例化
    }

    /**
     * 从请求中提取客户端真实 IP
     * 多级代理时 X-Forwarded-For 形如 "客户端IP, 代理1IP, 代理2IP"，
     * 取第一个（最左侧）即为真实客户端 IP。
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        for (String header : PROXY_HEADERS) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                return normalize(ip.split(",")[0].trim());
            }
        }
        return normalize(request.getRemoteAddr());
    }

    /**
     * 规范化 IP 地址
     * 处理规则：
     * - IPv6 localhost（0:0:0:0:0:0:0:1 / ::1）→ 127.0.0.1
     * - IPv4-mapped IPv6（::ffff:192.168.1.1）→ 192.168.1.1
     * - 其他 IP 原样返回
     */
    public static String normalize(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        if (ip.startsWith("::ffff:")) {
            return ip.substring(7);
        }
        return ip;
    }
}