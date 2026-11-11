package com.example.opsaiagent.inspection.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证有空格、引号和多值的凭据不会因局部替换泄漏。 */
class InspectionLogRedactorTest {
    /**
     * 凭据字段命中时整条隐藏，避免复杂值被部分替换。
     * @param message 包含测试凭据的日志
     * @return 无返回值
     */
    @ParameterizedTest
    @ValueSource(strings = {"password=secret with spaces", "Cookie: session=abc; other=xyz",
            "Authorization: Bearer abc.def.ghi", "{\"password\":\"escaped\\\"tail\"}", "api_key=value", "clientSecret=value"})
    void hidesCredentialBearingMessages(String message) {
        assertThat(new InspectionLogRedactor().redact(message)).isEqualTo("[含凭据字段的日志正文已隐藏]");
    }
}
