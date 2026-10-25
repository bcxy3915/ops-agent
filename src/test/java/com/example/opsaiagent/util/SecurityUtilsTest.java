package com.example.opsaiagent.util;

import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.rbac.context.UserContext;
import com.example.opsaiagent.security.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityUtils 单元测试
 * 覆盖：无认证 / 匿名用户 / 已认证用户 / 租户/部门/用户/权限上下文
 */
@DisplayName("安全工具类")
class SecurityUtilsTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        DeptContext.clear();
        UserContext.clear();
    }

    @Test
    @DisplayName("上下文：读取租户、部门、用户与权限")
    void currentContext() {
        TenantContext.set("t-default");
        DeptContext.set(5L);
        UserContext.set(new UserContext.CurrentUser(
                "u-1", "zhangwei", Set.of("DEPT_ADMIN"), Set.of("knowledge:review")));

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo("u-1");
        assertThat(SecurityUtils.getCurrentTenantId()).isEqualTo("t-default");
        assertThat(SecurityUtils.getCurrentDeptId()).isEqualTo(5L);
        assertThat(SecurityUtils.hasPermission("knowledge:review")).isTrue();
        assertThat(SecurityUtils.hasPermission("knowledge:publish")).isFalse();
        assertThat(SecurityUtils.hasPermission(null)).isFalse();
    }

    @Test
    @DisplayName("上下文：未设置时返回 null/false")
    void currentContext_empty() {
        assertThat(SecurityUtils.getCurrentUserId()).isNull();
        assertThat(SecurityUtils.getCurrentTenantId()).isNull();
        assertThat(SecurityUtils.getCurrentDeptId()).isNull();
        assertThat(SecurityUtils.hasPermission("knowledge:review")).isFalse();
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
