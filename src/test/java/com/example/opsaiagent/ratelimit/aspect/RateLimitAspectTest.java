package com.example.opsaiagent.ratelimit.aspect;

import com.example.opsaiagent.ratelimit.RateLimiter;
import com.example.opsaiagent.ratelimit.config.RateLimitProperties;
import com.example.opsaiagent.rbac.context.TenantContext;
import com.example.opsaiagent.rbac.context.UserContext;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.ratelimit.exception.RateLimitExceededException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitAspect 单元测试
 * 覆盖：放行 / 限流拦截 / GLOBAL、USER、IP 三种维度 key 构造
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("限流切面")
class RateLimitAspectTest {

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ProceedingJoinPoint joinPoint;

    private RateLimitAspect aspect;
    private RateLimitProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        aspect = new RateLimitAspect(rateLimiter, properties);
        TenantContext.clear();
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private RateLimit rateLimit(RateLimit.Dimension dimension) {
        RateLimit annotation = mock(RateLimit.class);
        when(annotation.key()).thenReturn("login");
        when(annotation.limit()).thenReturn(5);
        when(annotation.period()).thenReturn(60L);
        when(annotation.dimension()).thenReturn(dimension);
        return annotation;
    }

    @Test
    @DisplayName("around：允许通过时继续执行目标方法")
    void allowed_proceeds() throws Throwable {
        RateLimit annotation = rateLimit(RateLimit.Dimension.GLOBAL);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("ok");
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("around：触发限流时抛 RateLimitExceededException")
    void denied_throws() throws Throwable {
        RateLimit annotation = rateLimit(RateLimit.Dimension.GLOBAL);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("60 秒");

        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("GLOBAL 维度：key 为 prefix:global")
    void globalKey() throws Throwable {
        RateLimit annotation = rateLimit(RateLimit.Dimension.GLOBAL);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(keyCaptor.capture(), anyInt(), anyLong());
        assertThat(keyCaptor.getValue()).isEqualTo("login:global");
    }

    @Test
    @DisplayName("USER 维度：key 使用租户与稳定用户 ID")
    void userKey() throws Throwable {
        TenantContext.set("tenant-a");
        UserContext.set(new UserContext.CurrentUser("uid-1", "admin", null, null));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        RateLimit annotation = rateLimit(RateLimit.Dimension.USER);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(keyCaptor.capture(), anyInt(), anyLong());
        assertThat(keyCaptor.getValue()).isEqualTo("login:user:8:tenant-a:5:uid-1");
    }

    @Test
    @DisplayName("USER 维度：缺少身份时拒绝，不进入业务")
    void userKeyAnonymous() throws Throwable {
        RateLimit annotation = mock(RateLimit.class);
        when(annotation.key()).thenReturn("login");
        when(annotation.dimension()).thenReturn(RateLimit.Dimension.USER);
        assertThatThrownBy(() -> aspect.around(joinPoint, annotation))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyLong());
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("IP 维度：key 包含客户端 IP")
    void ipKey() throws Throwable {
        properties.setTrustForwardedHeaders(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RateLimit annotation = rateLimit(RateLimit.Dimension.IP);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(keyCaptor.capture(), anyInt(), anyLong());
        assertThat(keyCaptor.getValue()).isEqualTo("login:ip:1.2.3.4");
    }

    @Test
    @DisplayName("IP 维度：无请求上下文时用 unknown")
    void ipKeyUnknown() throws Throwable {
        RateLimit annotation = rateLimit(RateLimit.Dimension.IP);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(keyCaptor.capture(), anyInt(), anyLong());
        assertThat(keyCaptor.getValue()).isEqualTo("login:ip:unknown");
    }

    @Test
    void untrustedForwardedHeaderCannotCreateNewIpQuota() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        aspect.around(joinPoint, rateLimit(RateLimit.Dimension.IP));
        verify(rateLimiter).tryAcquire("login:ip:10.0.0.1", 5, 60);
    }

    @Test
    void configuredPolicyOverridesAnnotation() throws Throwable {
        var policy = new RateLimitProperties.Policy();
        policy.setLimit(2);
        policy.setPeriod(120);
        properties.getPolicies().put("login", policy);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        RateLimit annotation = mock(RateLimit.class);
        when(annotation.key()).thenReturn("login");
        when(annotation.dimension()).thenReturn(RateLimit.Dimension.GLOBAL);
        aspect.around(joinPoint, annotation);
        verify(rateLimiter).tryAcquire("login:global", 2, 120);
    }

    @Test
    void limiterFailureNeverEntersBusiness() throws Throwable {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenThrow(
                new com.example.opsaiagent.ratelimit.exception.RateLimitUnavailableException(new RuntimeException()));
        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimit(RateLimit.Dimension.GLOBAL)))
                .isInstanceOf(com.example.opsaiagent.ratelimit.exception.RateLimitUnavailableException.class);
        verify(joinPoint, never()).proceed();
    }
}
