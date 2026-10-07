package com.example.opsaiagent.crypto.util;

import com.example.opsaiagent.crypto.config.CryptoProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密工具
 * 存储格式：{@code Base64(IV) + ":" + Base64(ciphertext+tag)}
 * - IV 每次随机生成，长度 12 字节（GCM 推荐值）
 * - Tag 长度 128 位
 * - 密文总长度 = IV(16 Base64) + ":" + 密文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AesGcmUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String SEPARATOR = ":";

    private final CryptoProperties properties;

    private SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    public void init() {
        String keyBase64 = properties.getAesKey();
        if (keyBase64 == null || keyBase64.isEmpty()) {
            log.warn("[加密] 未配置 ops-agent.crypto.aes-key，敏感字段加密功能将不可用");
            return;
        }
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "AES-256 密钥长度必须为 32 字节，当前为 " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        log.info("[加密] AES-256-GCM 初始化完成，密钥版本={}", properties.getKeyVersion());
    }

    /**
     * 加密
     * @param plaintext 明文，null 或空串直接返回 null
     * @return Base64(IV):Base64(密文)，失败返回 null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        ensureInitialized();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv)
                    + SEPARATOR
                    + Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception e) {
            log.error("[加密] 加密失败", e);
            return null;
        }
    }

    /**
     * 解密
     * @param encrypted Base64(IV):Base64(密文)
     * @return 明文，失败返回 null
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return null;
        ensureInitialized();
        try {
            int idx = encrypted.indexOf(SEPARATOR);
            if (idx <= 0) {
                log.warn("[加密] 密文格式错误，缺少分隔符");
                return null;
            }
            byte[] iv = Base64.getDecoder().decode(encrypted.substring(0, idx));
            byte[] cipherText = Base64.getDecoder().decode(encrypted.substring(idx + 1));

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherText);

            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[加密] 解密失败", e);
            return null;
        }
    }

    private void ensureInitialized() {
        if (secretKey == null) {
            throw new IllegalStateException("AES 密钥未初始化，请配置 ops-agent.crypto.aes-key");
        }
    }
}