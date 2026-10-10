package com.example.opsaiagent.security.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtService 单元测试
 * 覆盖：生成 / 解析 / 校验 / 提取字段 / 过期 / 篡改 / 错误密钥 / 配置项
 */
@DisplayName("JWT 服务")
class JwtServiceTest {

    // HS256 要求 secret ≥ 32 字节
    private static final String SECRET = "test-secret-key-for-jwt-signing-must-be-at-least-32-bytes-long";
    private static final long EXPIRATION = 3600L;
    private static final String ISSUER = "ops-agent-test";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION, ISSUER);
    }

    // ==================== generateToken ====================

    @Test
    @DisplayName("generateToken：生成可解析的有效 Token")
    void generateToken_thenParse() {
        String token = jwtService.generateToken("admin", "ADMIN");

        assertThat(token).isNotBlank();
        Claims claims = jwtService.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    // ==================== extractUsername / extractRole ====================

    @Test
    @DisplayName("extractUsername：正确提取用户名")
    void extractUsername() {
        String token = jwtService.generateToken("alice", "OPERATOR");
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
    }

    @Test
    @DisplayName("extractRole：正确提取角色")
    void extractRole() {
        String token = jwtService.generateToken("bob", "VIEWER");
        assertThat(jwtService.extractRole(token)).isEqualTo("VIEWER");
    }

    // ==================== isValid ====================

    @Test
    @DisplayName("isValid：有效 Token 返回 true")
    void isValid_validToken() {
        String token = jwtService.generateToken("admin", "ADMIN");
        assertThat(jwtService.isValid(token)).isTrue();
    }

    @Test
    @DisplayName("isValid：篡改 Token 返回 false")
    void isValid_tamperedToken() {
        String token = jwtService.generateToken("admin", "ADMIN");
        // 改动末尾 4 位
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThat(jwtService.isValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("isValid：用不同密钥签名返回 false")
    void isValid_wrongSecret() {
        JwtService other = new JwtService(
                "another-secret-key-different-from-the-original-one-32bytes", 3600, ISSUER);
        String token = other.generateToken("admin", "ADMIN");

        assertThat(jwtService.isValid(token)).isFalse();
    }

    @Test
    @DisplayName("isValid：格式错误的字符串返回 false")
    void isValid_malformedToken() {
        assertThat(jwtService.isValid("not.a.token")).isFalse();
        assertThat(jwtService.isValid("")).isFalse();
        assertThat(jwtService.isValid("abc")).isFalse();
        assertThat(jwtService.isValid(null)).isFalse();
    }

    // ==================== 过期 ====================

    @Test
    @DisplayName("parseToken：过期 Token 抛异常，isValid 返回 false")
    void parseToken_expired() throws InterruptedException {
        // 1 秒过期
        JwtService shortLived = new JwtService(SECRET, 1L, ISSUER);
        String token = shortLived.generateToken("admin", "ADMIN");

        Thread.sleep(1500);

        assertThatThrownBy(() -> shortLived.parseToken(token))
                .isInstanceOf(Exception.class);
        assertThat(shortLived.isValid(token)).isFalse();
    }

    // ==================== 配置 ====================

    @Test
    @DisplayName("getExpirationSeconds：返回配置的过期秒数")
    void getExpirationSeconds() {
        assertThat(jwtService.getExpirationSeconds()).isEqualTo(EXPIRATION);
    }
}