package com.example.opsaiagent.audit.annotation;

import java.lang.annotation.*;

/**
 * 审计日志注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * 操作类型，如 REGISTER_SERVICE / DELETE_SERVICE
     * @return 操作类型
     */
    String operation();

    /**
     * 操作描述，如 "注册服务"
     * @return 操作描述
     */
    String description() default "";

    /**
     * 是否记录请求参数
     * @return 是否记录请求参数
     */
    boolean recordParams() default true;
}
