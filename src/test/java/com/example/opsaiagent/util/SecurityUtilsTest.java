package com.example.opsaiagent.util;

import com.example.opsaiagent.security.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityUtils 单元测试
 * 覆盖：无认证 / 匿名用户 / 已认证用户
 */
@DisplayName("安全工具类")
class SecurityUtilsTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getCurrentUsername：无认证返回 null")
    void getCurrentUsername_noAuth() {
        assertThat(SecurityUtils.getCurrentUsername()).isNull();
    }

    @Test
    @DisplayName("getCurrentUsername：匿名用户返回 null")
    void getCurrentUsername_anonymous() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "anonymousUser", null, List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(SecurityUtils.getCurrentUsername()).isNull();
    }

    @Test
    @DisplayName("getCurrentUsername：已认证用户返回用户名")
    void getCurrentUsername_authenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(SecurityUtils.getCurrentUsername()).isEqualTo("admin");
    }
}
