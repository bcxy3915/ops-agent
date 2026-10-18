package com.example.opsaiagent.ratelimit.aspect;

import com.example.opsaiagent.ratelimit.RateLimiter;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.ratelimit.exception.RateLimitExceededException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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

    @InjectMocks
    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
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
    @DisplayName("USER 维度：key 包含当前用户名")
    void userKey() throws Throwable {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        RateLimit annotation = rateLimit(RateLimit.Dimension.USER);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(keyCaptor.capture(), anyInt(), anyLong());
        assertThat(keyCaptor.getValue()).isEqualTo("login:user:admin");
    }

    @Test
    @DisplayName("USER 维度：未登录时用 anonymous")
    void userKeyAnonymous() throws Throwable {
        RateLimit annotation = rateLimit(RateLimit.Dimension.USER);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(keyCaptor.capture(), anyInt(), anyLong());
        assertThat(keyCaptor.getValue()).isEqualTo("login:user:anonymous");
    }

    @Test
    @DisplayName("IP 维度：key 包含客户端 IP")
    void ipKey() throws Throwable {
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
}
