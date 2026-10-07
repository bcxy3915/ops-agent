package com.example.opsaiagent.crypto.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HashUtil 单元测试
 * 覆盖：稳定性、唯一性、格式、边界值
 */
@DisplayName("SHA-256 哈希工具")
class HashUtilTest {

    private HashUtil hashUtil;

    @BeforeEach
    void setUp() {
        hashUtil = new HashUtil();
    }

    @Test
    @DisplayName("相同输入：哈希稳定一致")
    void hash_sameInput_consistent() {
        String h1 = hashUtil.sha256("13812345678");
        String h2 = hashUtil.sha256("13812345678");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("不同输入：哈希不同")
    void hash_differentInput_notEqual() {
        String h1 = hashUtil.sha256("13812345678");
        String h2 = hashUtil.sha256("13812345679");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("输出格式：64 位小写 hex")
    void hash_outputFormat() {
        String hash = hashUtil.sha256("test");
        assertThat(hash)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("边界：null 输入返回 null")
    void hash_null_returnsNull() {
        assertThat(hashUtil.sha256(null)).isNull();
    }

    @Test
    @DisplayName("边界：空串返回 null")
    void hash_empty_returnsNull() {
        assertThat(hashUtil.sha256("")).isNull();
    }
}