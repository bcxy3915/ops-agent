package com.example.opsaiagent.security.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全配置单元测试（CORS 白名单）。
 */
@DisplayName("安全配置")
class SecurityConfigTest {

    @Test
    @DisplayName("CORS：来源白名单 + 关闭凭证")
    void cors_allowlistWithoutCredentials() {
        SecurityConfig config = new SecurityConfig(null, null, null);
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://a.com, http://b.com");

        CorsConfiguration cors = config.buildCorsConfiguration();

        assertThat(cors.getAllowedOrigins()).containsExactly("http://a.com", "http://b.com");
        assertThat(cors.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("parseOrigins：逗号分隔并去空白")
    void parseOrigins_trimsAndFilters() {
        assertThat(SecurityConfig.parseOrigins("http://a.com, , http://b.com"))
                .containsExactly("http://a.com", "http://b.com");
        assertThat(SecurityConfig.parseOrigins("  ")).isEmpty();
        assertThat(SecurityConfig.parseOrigins(null)).isEmpty();
    }

    /**
     * 验证开发前端来源白名单允许本机两种地址，并拒绝其他来源。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void cors_allowsFrontendPort8888Only() {
        SecurityConfig config = new SecurityConfig(null, null, null);
        ReflectionTestUtils.setField(config, "allowedOrigins",
                "http://localhost:8888,http://127.0.0.1:8888");
        CorsConfiguration cors = config.buildCorsConfiguration();
        assertThat(cors.checkOrigin("http://localhost:8888")).isEqualTo("http://localhost:8888");
        assertThat(cors.checkOrigin("http://127.0.0.1:8888")).isEqualTo("http://127.0.0.1:8888");
        assertThat(cors.checkOrigin("https://untrusted.example")).isNull();
    }
}
