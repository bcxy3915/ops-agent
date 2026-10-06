package com.example.opsaiagent.sensitive.util;

import com.example.opsaiagent.sensitive.SensitiveType;

import java.util.Arrays;

/**
 * 敏感字段脱敏工具（无状态，纯函数）
 * 与 AuditLogAspect 配合使用：Aspect 负责递归遍历 JSON，
 * 本类负责单个字段名 + 字段值的匹配和脱敏。
 */
public final class SensitiveUtil {

    private SensitiveUtil() {
    }

    /**
     * 判断字段名是否敏感：命中 SensitiveType 中任一关键词即视为敏感
     * 使用 contains 而非 equals，兼容 password / oldPassword / userPassword 等命名
     */
    public static boolean isSensitiveField(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return Arrays.stream(SensitiveType.values())
                .flatMap(t -> Arrays.stream(t.getFieldNames()))
                .anyMatch(lower::contains);
    }

    /**
     * 按敏感类型精细脱敏
     * 匹配顺序：PASSWORD → PHONE → EMAIL → ID_CARD → BANK_CARD → NAME
     * 未命中任何类型时兜底返回 "***"
     */
    public static String mask(String fieldName, String value) {
        if (value == null || value.isEmpty()) return "***";

        String lower = fieldName.toLowerCase();

        for (SensitiveType type : SensitiveType.values()) {
            boolean matched = Arrays.stream(type.getFieldNames()).anyMatch(lower::contains);
            if (!matched) continue;

            return switch (type) {
                case PASSWORD -> "******";
                case PHONE -> value.length() >= 7
                        ? value.substring(0, 3) + "****" + value.substring(value.length() - 4)
                        : "***";
                case EMAIL -> {
                    int at = value.indexOf('@');
                    if (at <= 0) yield "***";
                    String local = value.substring(0, at);
                    yield (local.length() <= 1 ? "*" : local.charAt(0) + "***")
                            + value.substring(at);
                }
                case ID_CARD -> value.length() >= 10
                        ? value.substring(0, 3) + "********" + value.substring(value.length() - 4)
                        : "***";
                case BANK_CARD -> value.length() >= 8
                        ? value.substring(0, 4) + " **** **** " + value.substring(value.length() - 4)
                        : "***";
                case NAME -> value.length() <= 1
                        ? value
                        : value.charAt(0) + "*".repeat(value.length() - 1);
            };
        }
        return "***";
    }
}