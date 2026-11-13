package com.example.opsaiagent.ratelimit.aspect;

import com.example.opsaiagent.ratelimit.RateLimiter;
import com.example.opsaiagent.ratelimit.config.RateLimitProperties;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.ratelimit.exception.RateLimitExceededException;
import com.example.opsaiagent.common.util.IpUtils;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
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
    private final RateLimitProperties properties;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit);

        var policy = properties.getPolicies().get(rateLimit.key());
        int limit = policy == null ? rateLimit.limit() : policy.getLimit();
        long period = policy == null ? rateLimit.period() : policy.getPeriod();
        boolean allowed = rateLimiter.tryAcquire(key, limit, period);

        if (!allowed) {
            log.warn("限流: policy={}, limit={}/{}, uri={}", rateLimit.key(), limit, period, getRequestUri());
            throw new RateLimitExceededException("请求过于频繁，请稍后重试（令牌补充周期 " + period + " 秒）");
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
                String tenantId = SecurityUtils.getCurrentTenantId();
                String userId = SecurityUtils.getCurrentUserId();
                if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
                    throw new AccessDeniedException("用户限流需要已认证的租户与用户身份");
                }
                // 长度前缀避免身份中含分隔符时产生 Key 碰撞。
                return prefix + ":user:" + tenantId.length() + ":" + tenantId + ":" + userId.length() + ":" + userId;
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
        return properties.isTrustForwardedHeaders()
                ? IpUtils.getClientIp(attributes.getRequest())
                : IpUtils.normalize(attributes.getRequest().getRemoteAddr());
    }

    private String getRequestUri() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest().getRequestURI() : "";
    }
}
