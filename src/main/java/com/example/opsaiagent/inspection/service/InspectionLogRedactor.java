package com.example.opsaiagent.inspection.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 日志专用脱敏，保留异常类型与业务描述，不将原始凭据送入模型上下文。 */
@Component
public class InspectionLogRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9+/=_\\-.]+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)\\b[\\w-]*(?:password|passwd|pwd|token|api[-_]?key|secret|authorization|cookie)[\\\"']?\\s*[:=]");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern USER_INFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+:[^\\s/@]+@");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");

    /**
     * 先脱敏再由调用方截断，避免被截断的凭据绕过识别。
     * @param text 有界原始消息
     * @return 常见凭据、邮箱及手机号脱敏后的消息
     */
    public String redact(String text) {
        // 凭据可能包含空格、转义引号或整段 Cookie；命中字段时整条隐藏，避免部分脱敏泄露尾部。
        if (SECRET_FIELD.matcher(text).find()) {
            return "[含凭据字段的日志正文已隐藏]";
        }
        String result = BEARER.matcher(text).replaceAll("[凭据已脱敏]");
        result = USER_INFO.matcher(result).replaceAll("$1[已脱敏]@");
        result = JWT.matcher(result).replaceAll("[令牌已脱敏]");
        result = EMAIL.matcher(result).replaceAll("[邮箱已脱敏]");
        result = PHONE.matcher(result).replaceAll("[手机号已脱敏]");
        return result.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
    }
}
