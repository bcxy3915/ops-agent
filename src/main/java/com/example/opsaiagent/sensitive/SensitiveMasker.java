package com.example.opsaiagent.sensitive;

/**
 * 脱敏策略实现
 * 统一原则：
 * - 空值/异常输入返回原值
 * - 标准长度才暴露首尾，非标准长度全遮
 */
public final class SensitiveMasker {

    private SensitiveMasker() {
    }

    public static String mask(String value, SensitiveType type) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (type) {
            case PASSWORD -> maskPassword(value);
            case PHONE -> maskPhone(value);
            case EMAIL -> maskEmail(value);
            case ID_CARD -> maskIdCard(value);
            case BANK_CARD -> maskBankCard(value);
            case NAME -> maskName(value);
        };
    }

    private static String maskPassword(String value) {
        return "******";
    }

    /** 手机号：11 位标准，其他全遮 */
    private static String maskPhone(String value) {
        if (value.length() != 11) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 3) + "****" + value.substring(7);
    }

    /** 邮箱：没有长度限制，保留首字母和域名 */
    private static String maskEmail(String value) {
        int atIdx = value.indexOf('@');
        if (atIdx <= 0) {
            return value.charAt(0) + "*".repeat(Math.max(0, value.length() - 1));
        }
        String local = value.substring(0, atIdx);
        String domain = value.substring(atIdx);
        if (local.length() <= 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }

    /** 身份证：18 位或 15 位标准，其他全遮 */
    private static String maskIdCard(String value) {
        if (value.length() != 18 && value.length() != 15) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 3)
                + "*".repeat(value.length() - 7)
                + value.substring(value.length() - 4);
    }

    /** 银行卡：16-19 位标准，其他全遮 */
    private static String maskBankCard(String value) {
        if (value.length() < 16 || value.length() > 19) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 4)
                + "*".repeat(value.length() - 8)
                + value.substring(value.length() - 4);
    }

    /** 姓名：保留首字，其余全遮 */
    private static String maskName(String value) {
        if (value.length() == 1) {
            return value;
        }
        return value.charAt(0) + "*".repeat(value.length() - 1);
    }
}