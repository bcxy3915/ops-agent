package com.example.opsaiagent.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IpUtils 单元测试
 * 覆盖：代理头提取 / remoteAddr 兜底 / IPv6 规范化
 */
@DisplayName("IP 工具类")
class IpUtilsTest {

    @Test
    @DisplayName("getClientIp：request 为 null 返回 unknown")
    void getClientIp_nullRequest() {
        assertThat(IpUtils.getClientIp(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("getClientIp：优先取 X-Forwarded-For 第一个 IP")
    void getClientIp_forwardedFor() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 10.0.0.1");

        assertThat(IpUtils.getClientIp(request)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("getClientIp：X-Forwarded-For 为 unknown 时回退 remoteAddr")
    void getClientIp_forwardedForUnknown() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("unknown");
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        assertThat(IpUtils.getClientIp(request)).isEqualTo("192.168.1.1");
    }

    @Test
    @DisplayName("getClientIp：使用 X-Real-IP")
    void getClientIp_xRealIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("5.6.7.8");

        assertThat(IpUtils.getClientIp(request)).isEqualTo("5.6.7.8");
    }

    @Test
    @DisplayName("getClientIp：无代理头时回退 remoteAddr")
    void getClientIp_remoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");

        assertThat(IpUtils.getClientIp(request)).isEqualTo("10.0.0.9");
    }

    @Test
    @DisplayName("normalize：IPv6 localhost 转 127.0.0.1")
    void normalize_ipv6Localhost() {
        assertThat(IpUtils.normalize("0:0:0:0:0:0:0:1")).isEqualTo("127.0.0.1");
        assertThat(IpUtils.normalize("::1")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("normalize：IPv4-mapped IPv6 去掉前缀")
    void normalize_ipv4Mapped() {
        assertThat(IpUtils.normalize("::ffff:192.168.1.1")).isEqualTo("192.168.1.1");
    }

    @Test
    @DisplayName("normalize：null 或空白返回 unknown")
    void normalize_blank() {
        assertThat(IpUtils.normalize(null)).isEqualTo("unknown");
        assertThat(IpUtils.normalize("   ")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("normalize：普通 IP 原样返回")
    void normalize_plain() {
        assertThat(IpUtils.normalize("1.2.3.4")).isEqualTo("1.2.3.4");
    }
}
