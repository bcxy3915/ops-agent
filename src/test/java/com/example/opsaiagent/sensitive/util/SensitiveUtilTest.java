package com.example.opsaiagent.sensitive.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveUtil 单元测试
 * 覆盖：字段名匹配、6 种脱敏策略、边界值（空串/短值/非标准长度）
 */
@DisplayName("敏感字段脱敏工具")
class SensitiveUtilTest {

    @Nested
    @DisplayName("字段名匹配 isSensitiveField")
    class IsSensitiveField {

        @Test
        @DisplayName("命中：password / oldPassword / userPassword")
        void match_passwordVariants() {
            assertThat(SensitiveUtil.isSensitiveField("password")).isTrue();
            assertThat(SensitiveUtil.isSensitiveField("oldPassword")).isTrue();
            assertThat(SensitiveUtil.isSensitiveField("user_password")).isTrue();
        }

        @Test
        @DisplayName("命中：手机号、邮箱、身份证")
        void match_phoneEmailId() {
            assertThat(SensitiveUtil.isSensitiveField("phone")).isTrue();
            assertThat(SensitiveUtil.isSensitiveField("mobile")).isTrue();
            assertThat(SensitiveUtil.isSensitiveField("email")).isTrue();
            assertThat(SensitiveUtil.isSensitiveField("idCard")).isTrue();
        }

        @Test
        @DisplayName("不命中：普通字段")
        void notMatch_normalFields() {
            assertThat(SensitiveUtil.isSensitiveField("username")).isFalse();
            assertThat(SensitiveUtil.isSensitiveField("title")).isFalse();
            assertThat(SensitiveUtil.isSensitiveField("description")).isFalse();
        }

        @Test
        @DisplayName("null 输入返回 false")
        void nullInput_returnsFalse() {
            assertThat(SensitiveUtil.isSensitiveField(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("脱敏策略 mask")
    class Mask {

        @Test
        @DisplayName("手机号：保留前 3 后 4")
        void mask_phone() {
            assertThat(SensitiveUtil.mask("phone", "13812345678"))
                    .isEqualTo("138****5678");
        }

        @Test
        @DisplayName("手机号：短值全遮")
        void mask_phoneShort() {
            assertThat(SensitiveUtil.mask("phone", "123456"))
                    .isEqualTo("***");
        }

        @Test
        @DisplayName("邮箱：保留首字符 + 域名")
        void mask_email() {
            assertThat(SensitiveUtil.mask("email", "zhangsan@example.com"))
                    .isEqualTo("z***@example.com");
        }

        @Test
        @DisplayName("邮箱：非法格式（无 @）全遮")
        void mask_emailInvalid() {
            assertThat(SensitiveUtil.mask("email", "notanemail"))
                    .isEqualTo("***");
        }

        @Test
        @DisplayName("身份证：保留前 3 后 4")
        void mask_idCard() {
            assertThat(SensitiveUtil.mask("idCard", "110101199001011234"))
                    .isEqualTo("110********1234");
        }

        @Test
        @DisplayName("银行卡：保留前 4 后 4")
        void mask_bankCard() {
            assertThat(SensitiveUtil.mask("bankCard", "6222021234567890123"))
                    .isEqualTo("6222 **** **** 0123");
        }

        @Test
        @DisplayName("姓名：保留姓氏")
        void mask_name() {
            assertThat(SensitiveUtil.mask("realName", "张三丰"))
                    .isEqualTo("张**");
        }

        @Test
        @DisplayName("密码：全部掩码")
        void mask_password() {
            assertThat(SensitiveUtil.mask("password", "admin123"))
                    .isEqualTo("******");
        }

        @Test
        @DisplayName("null / 空串：返回 ***")
        void mask_nullOrEmpty() {
            assertThat(SensitiveUtil.mask("phone", null)).isEqualTo("***");
            assertThat(SensitiveUtil.mask("phone", "")).isEqualTo("***");
        }

        @Test
        @DisplayName("未命中任何类型：兜底 ***")
        void mask_unknownField() {
            assertThat(SensitiveUtil.mask("someField", "abc")).isEqualTo("***");
        }
    }
}