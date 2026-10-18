package com.example.opsaiagent.sensitive.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维敏感信息扫描工具测试（P1）
 * 覆盖：IP / DOMAIN / INSTANCE 三类识别、去重计数、脱敏替换、空与 null 输入。
 */
@DisplayName("运维敏感信息扫描工具")
class OpsSensitiveScannerTest {

    @Test
    @DisplayName("扫描：识别 IP / DOMAIN / INSTANCE 三类敏感信息")
    void scan_types() {
        String text = "内网地址 192.168.1.10，域名 api.example.com，实例 i-2a3b4c5d，"
                + "还有公网 10.20.30.40";

        List<OpsSensitiveScanner.SensitiveItem> items = OpsSensitiveScanner.scan(text);

        assertThat(items).hasSize(4);
        assertThat(items).extracting(OpsSensitiveScanner.SensitiveItem::getType)
                .contains("IP", "DOMAIN", "INSTANCE");
        assertThat(items).anyMatch(i -> "IP".equals(i.getType()) && "192.168.1.10".equals(i.getRaw()));
        assertThat(items).anyMatch(i -> "DOMAIN".equals(i.getType()) && "api.example.com".equals(i.getRaw()));
        assertThat(items).anyMatch(i -> "INSTANCE".equals(i.getType()) && "i-2a3b4c5d".equals(i.getRaw()));
    }

    @Test
    @DisplayName("扫描：同一敏感信息多次出现时去重并累计次数")
    void scan_countDeduplicates() {
        String text = "IP 为 172.16.0.8，备用 172.16.0.8，另一个 172.16.0.9";

        List<OpsSensitiveScanner.SensitiveItem> items = OpsSensitiveScanner.scan(text);

        OpsSensitiveScanner.SensitiveItem ip8 = items.stream()
                .filter(i -> "172.16.0.8".equals(i.getRaw())).findFirst().orElseThrow();
        assertThat(ip8.getCount()).isEqualTo(2);
        OpsSensitiveScanner.SensitiveItem ip9 = items.stream()
                .filter(i -> "172.16.0.9".equals(i.getRaw())).findFirst().orElseThrow();
        assertThat(ip9.getCount()).isEqualTo(1);
        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("扫描：无效 IP（超出 255）不识别")
    void scan_invalidIpNotMatched() {
        String text = "999.999.1.1 不是有效 IP，1.2.3.4 才是";

        List<OpsSensitiveScanner.SensitiveItem> items = OpsSensitiveScanner.scan(text);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getRaw()).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("扫描：普通单词与数字不误报，无敏感信息时返回空列表")
    void scan_cleanText() {
        assertThat(OpsSensitiveScanner.scan("服务正常，耗时 23ms")).isEmpty();
        assertThat(OpsSensitiveScanner.scan("")).isEmpty();
        assertThat(OpsSensitiveScanner.scan(null)).isEmpty();
    }

    @Test
    @DisplayName("脱敏：IP/域名/实例分别替换为对应掩码，非敏感部分保留")
    void mask_replacesAll() {
        String text = "连接 192.168.1.10 的 api.example.com 实例 i-2a3b4c5d 完成";

        String masked = OpsSensitiveScanner.mask(text);

        assertThat(masked).isEqualTo("连接 *.*.*.* 的 ***.example.com 实例 ***-**** 完成");
    }

    @Test
    @DisplayName("脱敏：空文本与 null 原样返回")
    void mask_blankUnchanged() {
        assertThat(OpsSensitiveScanner.mask("")).isEqualTo("");
        assertThat(OpsSensitiveScanner.mask(null)).isNull();
        assertThat(OpsSensitiveScanner.mask("无敏感内容")).isEqualTo("无敏感内容");
    }

    @Test
    @DisplayName("扫描：多级域名与跨片段实例 ID 边界识别")
    void scan_boundary() {
        String text = "跳板机 jump.ops.corp.local，数据库实例 rds-2026-01-abc";

        List<OpsSensitiveScanner.SensitiveItem> items = OpsSensitiveScanner.scan(text);

        assertThat(items).anyMatch(i -> "jump.ops.corp.local".equals(i.getRaw()));
        assertThat(items).anyMatch(i -> "rds-2026-01-abc".equals(i.getRaw()));
    }
}
