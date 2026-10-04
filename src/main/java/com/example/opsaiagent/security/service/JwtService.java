package com.example.opsaiagent.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expirationSeconds;
    private final String issuer;

    public JwtService(
            @Value("${ops-agent.security.jwt.secret}") String secret,
            @Value("${ops-agent.security.jwt.expiration:7200}") long expirationSeconds,
            @Value("${ops-agent.security.jwt.issuer:ops-agent}") String issuer) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
        this.issuer = issuer;
    }

    /**
     * 生成 Token
     * @param username 用户名
     * @param role 角色
     * @return Token
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationSeconds * 1000);

        return Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 Token
     * @param token Token
     * @return Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 Token 是否有效
     * @param token Token
     * @return 是否有效
     */
    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Token 中提取用户名
     * @param token Token
     * @return 用户名
     */
    public String extractUsername(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 从 Token 中提取角色
     * @param token Token
     * @return 角色
     */
    public String extractRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    /**
     * 获取 Token 过期时间
     * @return 过期时间
     */
    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}