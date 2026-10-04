package com.example.opsaiagent;

import com.example.opsaiagent.util.IpUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class IpUtilsTest {

    @Test
    void testNormalize() {
        assertEquals("127.0.0.1", IpUtils.normalize("0:0:0:0:0:0:0:1"));
        assertEquals("127.0.0.1", IpUtils.normalize("::1"));
        assertEquals("192.168.1.100", IpUtils.normalize("::ffff:192.168.1.100"));
        assertEquals("10.0.0.1", IpUtils.normalize("10.0.0.1"));
        assertEquals("unknown", IpUtils.normalize(null));
        assertEquals("unknown", IpUtils.normalize(""));
    }

    @Test
    void testGetClientIpWithXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1, 10.0.0.2");
        assertEquals("203.0.113.5", IpUtils.getClientIp(request));
    }

    @Test
    void testGetClientIpFallbackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        assertEquals("192.168.1.50", IpUtils.getClientIp(request));
    }
}