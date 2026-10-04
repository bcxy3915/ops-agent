package com.example.opsaiagent.sensitive;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.*;

/**
 * 敏感信息脱敏注解
 * 用法：
 *   注解@Sensitive(type = SensitiveType.PHONE)
 *   private String phone;
 * 原理：
 *   注解上带 @JsonSerialize(using = SensitiveSerializer.class)，
 *   标记的字段在 JSON 序列化时自动走脱敏逻辑。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@JacksonAnnotationsInside
@JsonSerialize(using = SensitiveSerializer.class)
public @interface Sensitive {

    SensitiveType value();
}