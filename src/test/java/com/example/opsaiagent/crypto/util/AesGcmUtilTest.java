package com.example.opsaiagent.crypto.util;

import com.example.opsaiagent.crypto.config.CryptoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AesGcmUtil 单元测试
 * 覆盖：加解密往返、IV 随机性、边界值、密文完整性、异常场景
 */
@DisplayName("AES-256-GCM 加解密工具")
class AesGcmUtilTest {

    private AesGcmUtil aesGcmUtil;

    @BeforeEach
    void setUp() {
        // 固定 32 字节密钥（0~31），保证测试可重复
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) i;
        }

        CryptoProperties props = new CryptoProperties();
        props.setAesKey(Base64.getEncoder().encodeToString(key));
        props.setKeyVersion("test");

        aesGcmUtil = new AesGcmUtil(props);
        aesGcmUtil.init();
    }

    // ==================== 正常流程 ====================

    @Test
    @DisplayName("加解密往返：手机号明文")
    void encryptDecrypt_phone() {
        String plain = "13812345678";
        String cipher = aesGcmUtil.encrypt(plain);

        assertThat(cipher).isNotEqualTo(plain).contains(":");
        assertThat(aesGcmUtil.decrypt(cipher)).isEqualTo(plain);
    }

    @Test
    @DisplayName("加解密往返：中文 + 特殊字符 + emoji")
    void encryptDecrypt_specialChars() {
        String plain = "运维智能体 🚀 测试—中文字符 / 表情";
        String cipher = aesGcmUtil.encrypt(plain);
        assertThat(aesGcmUtil.decrypt(cipher)).isEqualTo(plain);
    }

    @Test
    @DisplayName("IV 随机：同一明文两次加密结果不同")
    void encrypt_sameTextTwice_producesDifferentCipher() {
        String plain = "admin@example.com";
        String c1 = aesGcmUtil.encrypt(plain);
        String c2 = aesGcmUtil.encrypt(plain);

        assertThat(c1).isNotEqualTo(c2);           // IV 不同
        assertThat(aesGcmUtil.decrypt(c1)).isEqualTo(plain);
        assertThat(aesGcmUtil.decrypt(c2)).isEqualTo(plain);
    }

    // ==================== 边界值 ====================

    @Test
    @DisplayName("加密：null 输入返回 null")
    void encrypt_null_returnsNull() {
        assertThat(aesGcmUtil.encrypt(null)).isNull();
    }

    @Test
    @DisplayName("加密：空串返回 null")
    void encrypt_empty_returnsNull() {
        assertThat(aesGcmUtil.encrypt("")).isNull();
    }

    @Test
    @DisplayName("解密：null / 空串返回 null")
    void decrypt_nullOrEmpty_returnsNull() {
        assertThat(aesGcmUtil.decrypt(null)).isNull();
        assertThat(aesGcmUtil.decrypt("")).isNull();
    }

    // ==================== 完整性校验 ====================

    @Test
    @DisplayName("解密：格式错误（缺少分隔符）返回 null")
    void decrypt_malformed_returnsNull() {
        assertThat(aesGcmUtil.decrypt("no-separator-here")).isNull();
    }

    @Test
    @DisplayName("解密：密文被篡改返回 null（GCM 完整性校验生效）")
    void decrypt_tamperedCipher_returnsNull() {
        String cipher = aesGcmUtil.encrypt("hello");
        // 改掉密文最后一个字符
        String tampered = cipher.substring(0, cipher.length() - 2) + "AA";

        assertThat(aesGcmUtil.decrypt(tampered)).isNull();
    }

    // ==================== 异常场景 ====================

    @Test
    @DisplayName("初始化：密钥长度不是 32 字节时抛异常")
    void init_wrongKeyLength_throws() {
        CryptoProperties props = new CryptoProperties();
        props.setAesKey(Base64.getEncoder().encodeToString(new byte[16])); // 只有 16 字节

        AesGcmUtil util = new AesGcmUtil(props);
        assertThatThrownBy(util::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}