package com.example.opsaiagent.sensitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveMasker 单元测试
 * 覆盖：密码 / 手机号 / 邮箱 / 身份证 / 银行卡 / 姓名 六类脱敏策略
 */
@DisplayName("敏感信息脱敏")
class SensitiveMaskerTest {

    // ==================== 空值 ====================

    @Test
    @DisplayName("mask：null 原样返回")
    void mask_null() {
        assertThat(SensitiveMasker.mask(null, SensitiveType.PHONE)).isNull();
    }

    @Test
    @DisplayName("mask：空串原样返回")
    void mask_empty() {
        assertThat(SensitiveMasker.mask("", SensitiveType.PHONE)).isEmpty();
    }

    // ==================== 密码 ====================

    @Test
    @DisplayName("密码：全部替换为 ******")
    void maskPassword() {
        assertThat(SensitiveMasker.mask("admin123", SensitiveType.PASSWORD)).isEqualTo("******");
    }

    // ==================== 手机号 ====================

    @Test
    @DisplayName("手机号：11 位保留前 3 后 4")
    void maskPhone_standard() {
        assertThat(SensitiveMasker.mask("13812345678", SensitiveType.PHONE))
                .isEqualTo("138****5678");
    }

    @Test
    @DisplayName("手机号：非 11 位全遮")
    void maskPhone_nonStandard() {
        assertThat(SensitiveMasker.mask("12345", SensitiveType.PHONE))
                .isEqualTo("*****");
    }

    // ==================== 邮箱 ====================

    @Test
    @DisplayName("邮箱：保留首字母与域名")
    void maskEmail_normal() {
        assertThat(SensitiveMasker.mask("admin@example.com", SensitiveType.EMAIL))
                .isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("邮箱：本地部分只有一位")
    void maskEmail_singleLocal() {
        assertThat(SensitiveMasker.mask("a@example.com", SensitiveType.EMAIL))
                .isEqualTo("*@example.com");
    }

    @Test
    @DisplayName("邮箱：无 @ 时首位保留其余遮蔽")
    void maskEmail_noAt() {
        assertThat(SensitiveMasker.mask("abcdef", SensitiveType.EMAIL))
                .isEqualTo("a*****");
    }

    // ==================== 身份证 ====================

    @Test
    @DisplayName("身份证：18 位保留前 3 后 4")
    void maskIdCard_18() {
        assertThat(SensitiveMasker.mask("110101199003071234", SensitiveType.ID_CARD))
                .isEqualTo("110***********1234");
    }

    @Test
    @DisplayName("身份证：15 位保留前 3 后 4")
    void maskIdCard_15() {
        assertThat(SensitiveMasker.mask("110101900307123", SensitiveType.ID_CARD))
                .isEqualTo("110********7123");
    }

    @Test
    @DisplayName("身份证：非标准长度全遮")
    void maskIdCard_nonStandard() {
        assertThat(SensitiveMasker.mask("12345", SensitiveType.ID_CARD))
                .isEqualTo("*****");
    }

    // ==================== 银行卡 ====================

    @Test
    @DisplayName("银行卡：16 位保留前 4 后 4")
    void maskBankCard_16() {
        assertThat(SensitiveMasker.mask("6222021234567890", SensitiveType.BANK_CARD))
                .isEqualTo("6222********7890");
    }

    @Test
    @DisplayName("银行卡：19 位保留前 4 后 4")
    void maskBankCard_19() {
        assertThat(SensitiveMasker.mask("6222021234567890123", SensitiveType.BANK_CARD))
                .isEqualTo("6222***********0123");
    }

    @Test
    @DisplayName("银行卡：非 16-19 位全遮")
    void maskBankCard_nonStandard() {
        assertThat(SensitiveMasker.mask("123456", SensitiveType.BANK_CARD))
                .isEqualTo("******");
    }

    // ==================== 姓名 ====================

    @Test
    @DisplayName("姓名：单字原样返回")
    void maskName_single() {
        assertThat(SensitiveMasker.mask("王", SensitiveType.NAME)).isEqualTo("王");
    }

    @Test
    @DisplayName("姓名：保留首字其余遮蔽")
    void maskName_multi() {
        assertThat(SensitiveMasker.mask("张三丰", SensitiveType.NAME)).isEqualTo("张**");
    }
}
