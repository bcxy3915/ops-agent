package com.example.opsaiagent.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UrlSafetyValidator 单元测试（SSRF 防护）。
 */
@DisplayName("出站 URL 安全校验")
class UrlSafetyValidatorTest {

    @Test
    @DisplayName("允许公网域名与公网 IP")
    void allowsPublicTargets() {
        assertThatCode(() -> UrlSafetyValidator.assertSafeWebhookUrl("https://example.com/hook/alert"))
                .doesNotThrowAnyException();
        assertThatCode(() -> UrlSafetyValidator.assertSafeWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://8.8.8.8/hook"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝非 http/https 协议")
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("ftp://example.com/hook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("拒绝回环地址")
    void rejectsLoopback() {
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://127.0.0.1/hook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网/回环");
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://[::1]/hook"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("拒绝内网与链路本地/元数据地址")
    void rejectsPrivateAndLinkLocal() {
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://10.0.0.1/hook"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://192.168.1.1/hook"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://172.16.0.1/hook"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("拒绝组播与任意地址")
    void rejectsMulticastAndAny() {
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://224.0.0.1/hook"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("http://0.0.0.0/hook"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("拒绝空地址")
    void rejectsEmpty() {
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlSafetyValidator.assertSafeWebhookUrl("https:///hook"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
