package com.example.opsaiagent.crypto.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * SHA-256 哈希工具
 * 用于敏感字段的精确查询：把明文哈希后存入 xxx_hash 列，
 * 查询时把查询条件也哈希后再比，避免明文落在索引里。
 * 建议加固定的 salt（从配置读），防止彩虹表攻击。
 */
@Slf4j
@Component
public class HashUtil {

    /** 固定 salt，建议从配置读，这里先硬编码演示 */
    private static final String SALT = "ops-agent-2026";

    /**
     * SHA-256 哈希，输出 64 字符小写 hex
     */
    public String sha256(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((SALT + plaintext).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("[哈希] SHA-256 失败", e);
            return null;
        }
    }
}