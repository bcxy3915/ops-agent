package com.example.opsaiagent.ratelimit.aspect;

import com.example.opsaiagent.ratelimit.RateLimiter;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.ratelimit.exception.RateLimitExceededException;
import com.example.opsaiagent.util.IpUtils;
import com.example.opsaiagent.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 限流切面
 * 只依赖 RateLimiter 接口，不关心具体实现（Bucket4j / Redis / Sentinel）。
 * 未来切换实现时，本类无需修改。
 */
@Slf4j
@Order(1)
@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "ops-agent.rate-limit.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RateLimitAspect {

    private final RateLimiter rateLimiter;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit);

        boolean allowed = rateLimiter.tryAcquire(key, rateLimit.limit(), rateLimit.period());

        if (!allowed) {
            log.warn("限流: key={}, limit={}/{}, uri={}", key, rateLimit.limit(), rateLimit.period(), getRequestUri());
            throw new RateLimitExceededException("请求过于频繁，请 " + rateLimit.period() + " 秒后再试");
        }

        return joinPoint.proceed();
    }

    /**
     * 构造限流 key：{业务标识}:{维度}:{具体值}
     */
    private String buildKey(RateLimit rateLimit) {
        String prefix = rateLimit.key();
        switch (rateLimit.dimension()) {
            case USER:
                String username = SecurityUtils.getCurrentUsername();
                return prefix + ":user:" + (username != null ? username : "anonymous");
            case GLOBAL:
                return prefix + ":global";
            case IP:
            default:
                return prefix + ":ip:" + getClientIp();
        }
    }

    private String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return "unknown";
        return IpUtils.getClientIp(attributes.getRequest());
    }

    private String getRequestUri() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest().getRequestURI() : "";
    }
}