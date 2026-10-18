package com.example.opsaiagent.sensitive.util;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运维敏感信息扫描工具（P1）
 * 检测并脱敏文本中的运维敏感信息：IP 地址、域名、云实例 ID。
 * 供知识文档上传质检（脱敏预览）使用。
 */
public final class OpsSensitiveScanner {

    /** 敏感类型：IP 地址 */
    public static final String TYPE_IP = "IP";
    /** 敏感类型：域名 */
    public static final String TYPE_DOMAIN = "DOMAIN";
    /** 敏感类型：云实例 ID */
    public static final String TYPE_INSTANCE = "INSTANCE";

    /** 检测到的敏感信息条目 */
    @Data
    @AllArgsConstructor
    public static class SensitiveItem {
        /** 敏感类型：IP / DOMAIN / INSTANCE */
        private String type;
        /** 原文 */
        private String raw;
        /** 脱敏后的展示值 */
        private String masked;
        /** 出现次数 */
        private int count;
    }

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b");

    private static final Pattern DOMAIN = Pattern.compile(
            "\\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}\\b");

    private static final Pattern INSTANCE = Pattern.compile(
            "\\b(?:i-|ecs-|rds-|instance-|host-)[a-zA-Z0-9_-]{4,}\\b");

    private OpsSensitiveScanner() {
    }

    /**
     * 扫描文本中的运维敏感信息
     * @param text 待扫描文本（可为 null）
     * @return 敏感信息条目列表（无敏感信息时为空列表）
     */
    public static List<SensitiveItem> scan(String text) {
        List<SensitiveItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        collect(IPV4, text, TYPE_IP, "*.*.*.*", items);
        collect(DOMAIN, text, TYPE_DOMAIN, "***.example.com", items);
        collect(INSTANCE, text, TYPE_INSTANCE, "***-****", items);
        return items;
    }

    /**
     * 对文本中的敏感信息做脱敏替换（保留非敏感部分）
     * @param text 原文
     * @return 脱敏预览文本
     */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = text;
        masked = IPV4.matcher(masked).replaceAll("*.*.*.*");
        masked = DOMAIN.matcher(masked).replaceAll("***.example.com");
        masked = INSTANCE.matcher(masked).replaceAll("***-****");
        return masked;
    }

    /**
     * 按正则收集敏感信息，去重统计次数
     */
    private static void collect(Pattern pattern, String text, String type,
                                String maskedValue, List<SensitiveItem> items) {
        Matcher m = pattern.matcher(text);
        List<String> raws = new ArrayList<>();
        while (m.find()) {
            raws.add(m.group());
        }
        if (raws.isEmpty()) {
            return;
        }
        for (String raw : raws) {
            boolean exists = false;
            for (SensitiveItem item : items) {
                if (type.equals(item.getType()) && raw.equals(item.getRaw())) {
                    item.setCount(item.getCount() + 1);
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                items.add(new SensitiveItem(type, raw, maskedValue, 1));
            }
        }
    }
}
