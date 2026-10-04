package com.example.opsaiagent.audit.aspect;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.service.AuditLogService;
import com.example.opsaiagent.util.IpUtils;
import com.example.opsaiagent.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 审计日志切面
 * 拦截所有标注了 {注解 AuditLog} 的方法，自动记录：
 * - 操作用户（登录场景从参数提取，其他场景从 SecurityContext 提取）
 * - 操作对象（服务名、用户名等）
 * - 请求上下文（IP、User-Agent、URI、Method）
 * - 请求参数（敏感字段自动脱敏）
 * - 执行结果（成功/失败、耗时、错误信息）
 * 设计要点：
 * 1. 异步保存日志，不阻塞业务响应
 * 2. 无论方法成功还是抛异常，都会记录（在 finally 中处理）
 * 3. 参数脱敏采用递归遍历，支持嵌套对象和数组
 */
@Slf4j
@Order(2)
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * 环绕通知：拦截所有标注 @AuditLog 的方法
     * 执行流程：
     * 1. 记录开始时间
     * 2. 收集审计信息（用户、IP、参数等）
     * 3. 执行原方法
     * 4. 在 finally 中记录耗时并异步保存日志
     */
    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();

        AuditLogEntity entity = new AuditLogEntity();
        entity.setOperation(auditLog.operation());
        entity.setCreatedAt(LocalDateTime.now());

        // 用户：优先从认证上下文取，登录场景则从参数提取
        entity.setUsername(resolveUsername(joinPoint));

        // HTTP 上下文：通过 RequestContextHolder 从当前线程获取请求对象
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            entity.setMethod(request.getMethod());
            entity.setUri(request.getRequestURI());
            entity.setIp(IpUtils.getClientIp(request));
            entity.setUserAgent(request.getHeader("User-Agent"));
        }

        entity.setTarget(resolveTarget(joinPoint));

        if (auditLog.recordParams()) {
            entity.setParams(extractParams(joinPoint));
        }

        try {
            Object result = joinPoint.proceed();
            entity.setResult("SUCCESS");
            return result;
        } catch (Throwable throwable) {
            // 失败也要记录，便于事后排查和审计
            entity.setResult("FAILURE");
            entity.setErrorMessage(truncate(throwable.getMessage(), 500));
            throw throwable;
        } finally {
            // 无论成功失败都要计算耗时并保存，放在 finally 保证一定执行
            entity.setDurationMs(System.currentTimeMillis() - startTime);
            auditLogService.save(entity);
        }
    }

    /**
     * 解析操作用户
     * 两种场景：
     * - 已认证请求：从 SecurityContext 拿（更权威）
     * - 登录等未认证请求：从方法参数里的 username 字段提取
     */
    private String resolveUsername(ProceedingJoinPoint joinPoint) {
        String username = SecurityUtils.getCurrentUsername();
        if (username != null) return username;
        return extractUsernameFromArgs(joinPoint);
    }

    /**
     * 从方法参数中反射提取 username
     * 场景：登录接口的 LoginRequest 参数包含 username，但没有经过认证
     * 做法：尝试调用每个参数的 getUsername() 方法，拿到第一个非空值
     */
    private String extractUsernameFromArgs(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Method method = arg.getClass().getMethod("getUsername");
                Object value = method.invoke(arg);
                if (value instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (Exception ignored) {
                // 参数没有 getUsername 方法，跳过
            }
        }
        return null;
    }

    /**
     * 解析操作对象
     * 优先用 username（登录场景，用户名即操作对象）
     * 否则取第一个 String 参数（如 @PathVariable 的服务名）
     */
    private String resolveTarget(ProceedingJoinPoint joinPoint) {
        String username = extractUsernameFromArgs(joinPoint);
        if (username != null) return username;
        return extractTarget(joinPoint);
    }

    /**
     * 从参数中提取 String 类型的操作对象
     * 通常是 @PathVariable 的 name 参数
     */
    private String extractTarget(ProceedingJoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null) return null;
            for (Object arg : args) {
                if (arg instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 参数脱敏 ====================

    /**
     * 提取并脱敏请求参数
     * 原理：
     * 1. 通过 MethodSignature 拿到参数名（依赖编译时 -parameters 参数）
     * 2. 将每个参数值转成 JsonNode
     * 3. 递归遍历 JsonNode，把敏感字段替换为 ***
     * 4. 序列化为 JSON 字符串
     */
    private String extractParams(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] paramNames = signature.getParameterNames();
            Object[] args = joinPoint.getArgs();

            if (paramNames == null || args == null) {
                return null;
            }

            ObjectNode root = objectMapper.createObjectNode();
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                String name = paramNames[i];
                Object value = args[i];

                // 跳过空值和 Servlet 对象（无法序列化且无意义）
                if (value == null || isServletObject(value)) {
                    continue;
                }

                root.set(name, sanitizeValue(value));
            }

            return truncate(objectMapper.writeValueAsString(root), 2000);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把对象转成 JsonNode 后递归脱敏
     */
    private JsonNode sanitizeValue(Object value) {
        JsonNode node = objectMapper.valueToTree(value);
        return sanitizeNode(node);
    }

    /**
     * 递归遍历 JsonNode：
     * - Object 类型：检查每个字段名，命中敏感词替换为 ***；否则递归
     * - Array 类型：对每个元素递归
     * - 其他类型：原样返回
     * 这样才能处理嵌套结构，比如：
     * {"request": {"username": "admin", "password": "***"}}
     */
    private JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (isSensitiveField(key)) {
                    result.put(key, "***");
                } else {
                    result.set(key, sanitizeNode(entry.getValue()));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(sanitizeNode(item)));
            return result;
        }
        return node;
    }

    /**
     * 敏感字段判断：字段名包含以下关键词之一即视为敏感
     * 使用 contains 而非 equals，兼容 password / oldPassword / userPassword 等命名
     */
    private boolean isSensitiveField(String name) {
        String lower = name.toLowerCase();
        return lower.contains("password")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("apikey");
    }

    /**
     * 判断是否为 Servlet/IO 对象，这类对象无法序列化，直接跳过
     */
    private boolean isServletObject(Object obj) {
        return obj instanceof HttpServletRequest
                || obj instanceof jakarta.servlet.http.HttpServletResponse
                || obj instanceof java.io.InputStream
                || obj instanceof java.io.OutputStream;
    }

    /**
     * 截断超长字符串，防止审计日志字段溢出
     */
    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}