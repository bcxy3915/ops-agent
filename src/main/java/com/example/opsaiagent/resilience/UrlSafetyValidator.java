package com.example.opsaiagent.resilience;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 出站 URL 安全校验（SSRF 防护）。
 *
 * 面向用户/租户可控的 webhook 等出站地址：仅允许 http/https，且拦截字面 IP 形式的内网、
 * 回环、链路本地、组播、任意地址。域名形式的 DNS 重绑定防护需在连接层做「解析即校验」，
 * 属于后续加固项。
 */
public final class UrlSafetyValidator {

    private UrlSafetyValidator() {
    }

    /**
     * 校验 webhook URL 是否安全，非法时抛 {@link IllegalArgumentException}。
     *
     * @param url 目标 URL
     */
    public static void assertSafeWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("webhook URL 为空");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("非法 webhook URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("webhook 仅支持 http/https: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("webhook URL 缺少主机名: " + url);
        }
        String literal = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        boolean ipLiteral = literal.contains(":") || literal.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (ipLiteral && isUnsafeAddress(literal)) {
            throw new IllegalArgumentException("禁止访问内网/回环/链路本地地址: " + url);
        }
    }

    private static boolean isUnsafeAddress(String literal) {
        try {
            InetAddress address = InetAddress.getByName(literal);
            return address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || address.isAnyLocalAddress();
        } catch (Exception e) {
            // 无法解析的字面 IP 一律视为不安全
            return true;
        }
    }
}
