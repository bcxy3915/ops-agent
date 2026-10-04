package com.example.opsaiagent;

import com.example.opsaiagent.sensitive.SensitiveMasker;
import com.example.opsaiagent.sensitive.SensitiveType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveMaskerTest {

    @Test
    void testPassword() {
        assertEquals("******", SensitiveMasker.mask("admin123", SensitiveType.PASSWORD));
        assertEquals("******", SensitiveMasker.mask("x", SensitiveType.PASSWORD));
    }

    @Test
    void testPhone() {
        // 11 位标准
        assertEquals("138****5678", SensitiveMasker.mask("13812345678", SensitiveType.PHONE));
        // 非 11 位全遮
        assertEquals("***", SensitiveMasker.mask("123", SensitiveType.PHONE));
        assertEquals("********", SensitiveMasker.mask("12345678", SensitiveType.PHONE));
    }

    @Test
    void testEmail() {
        assertEquals("a***@example.com", SensitiveMasker.mask("admin@example.com", SensitiveType.EMAIL));
        assertEquals("*@example.com", SensitiveMasker.mask("a@example.com", SensitiveType.EMAIL));
        // 没有 @ 符号，保留首字母，其余全遮
        assertEquals("a****", SensitiveMasker.mask("admin", SensitiveType.EMAIL));
    }

    @Test
    void testIdCard() {
        // 18 位标准
        assertEquals("110***********1234",
                SensitiveMasker.mask("110101199001011234", SensitiveType.ID_CARD));
        // 非标准长度全遮
        assertEquals("******", SensitiveMasker.mask("123456", SensitiveType.ID_CARD));
    }

    @Test
    void testBankCard() {
        // 19 位标准
        assertEquals("6222***********0123",
                SensitiveMasker.mask("6222021234567890123", SensitiveType.BANK_CARD));
        // 非标准长度全遮
        assertEquals("********", SensitiveMasker.mask("12345678", SensitiveType.BANK_CARD));
    }

    @Test
    void testName() {
        assertEquals("张**", SensitiveMasker.mask("张三丰", SensitiveType.NAME));
        assertEquals("张", SensitiveMasker.mask("张", SensitiveType.NAME));
    }

    @Test
    void testNullAndEmpty() {
        assertNull(SensitiveMasker.mask(null, SensitiveType.PHONE));
        assertEquals("", SensitiveMasker.mask("", SensitiveType.PHONE));
    }
}