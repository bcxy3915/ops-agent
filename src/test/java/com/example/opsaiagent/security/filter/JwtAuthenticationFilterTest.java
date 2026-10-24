package com.example.opsaiagent.security.filter;

import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.rbac.context.UserContext;
import com.example.opsaiagent.rbac.dto.UserAuthorities;
import com.example.opsaiagent.rbac.service.PermissionService;
import com.example.opsaiagent.security.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter 单元测试。
 *
 * 覆盖：无 Token / 非 Bearer / 有效 Token / 无效 Token / 解析异常 /
 * 多租户与权限点写入 / 请求结束上下文清理。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JWT 认证过滤器")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        DeptContext.clear();
        UserContext.clear();
    }

    @Test
    @DisplayName("doFilter：无 Authorization 头直接放行")
    void noHeader_passesThrough() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilter：非 Bearer 头直接放行")
    void nonBearerHeader_passesThrough() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic abc");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilter：有效 Token 写入认证上下文")
    void validToken_setsAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-123");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("admin");
        when(claims.get("role", String.class)).thenReturn("ADMIN");
        when(jwtService.isValid("token-123")).thenReturn(true);
        when(jwtService.parseToken("token-123")).thenReturn(claims);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("admin");
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter：Token 无效不写认证")
    void invalidToken_noAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
        when(jwtService.isValid("bad-token")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter：解析异常被捕获，不中断请求")
    void parseException_noAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
        when(jwtService.isValid("bad-token")).thenReturn(true);
        when(jwtService.parseToken("bad-token")).thenThrow(new RuntimeException("jwt parse error"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter：Token 合法但 role/roles 缺失时不写入 ROLE_null")
    void validTokenWithoutRole() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("operator");
        when(claims.get("role", String.class)).thenReturn(null);
        when(jwtService.isValid("token-1")).thenReturn(true);
        when(jwtService.parseToken("token-1")).thenReturn(claims);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).isEmpty();
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(response);
    }

    @Test
    @DisplayName("doFilter：多租户 Token 写入上下文与权限点，请求结束后清理")
    void validToken_withTenantAndPermissions() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-2");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("zhangwei");
        when(claims.get("role", String.class)).thenReturn("DEPT_ADMIN");
        when(claims.get("userId", String.class)).thenReturn("u-1");
        when(claims.get("tenantId", String.class)).thenReturn("t-default");
        when(claims.get("deptId")).thenReturn(5L);
        when(claims.get("roles")).thenReturn(List.of("DEPT_ADMIN"));
        when(jwtService.isValid("token-2")).thenReturn(true);
        when(jwtService.parseToken("token-2")).thenReturn(claims);
        when(permissionService.loadAuthorities("u-1"))
                .thenReturn(new UserAuthorities(Set.of("DEPT_ADMIN"), Set.of("knowledge:review")));

        // 在下游执行期间断言上下文存在，验证 ThreadLocal 已写入
        doAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo("t-default");
            assertThat(DeptContext.get()).isEqualTo(5L);
            assertThat(UserContext.get()).isNotNull();
            assertThat(UserContext.get().userId()).isEqualTo("u-1");
            assertThat(UserContext.get().permissions()).contains("knowledge:review");
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_DEPT_ADMIN", "knowledge:review");

        // 请求结束后上下文必须清理，避免线程池复用串号
        assertThat(TenantContext.get()).isNull();
        assertThat(DeptContext.get()).isNull();
        assertThat(UserContext.get()).isNull();
    }
}
