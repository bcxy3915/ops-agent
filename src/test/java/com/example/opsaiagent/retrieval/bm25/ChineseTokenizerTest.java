package com.example.opsaiagent.retrieval.bm25;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChineseTokenizer 单元测试
 * 覆盖：纯中文、纯英文、中英混合、数字、标点、大小写、边界值
 */
@DisplayName("中文分词器")
class ChineseTokenizerTest {

    private ChineseTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new ChineseTokenizer();
    }

    // ==================== 中文 ====================

    @Test
    @DisplayName("纯中文：单字 + 相邻二字组合")
    void tokenize_pureChinese() {
        List<String> tokens = tokenizer.tokenize("内存泄漏");
        assertThat(tokens).containsExactly(
                "内", "内存",
                "存", "存泄",
                "泄", "泄漏",
                "漏"
        );
    }

    @Test
    @DisplayName("中文单字：只有 1 个中文字符")
    void tokenize_singleChinese() {
        assertThat(tokenizer.tokenize("中")).containsExactly("中");
    }

    // ==================== 英文 / 数字 ====================

    @Test
    @DisplayName("纯英文：小写化并按空格切分")
    void tokenize_pureEnglish() {
        List<String> tokens = tokenizer.tokenize("Hello World");
        assertThat(tokens).containsExactly("hello", "world");
    }

    @Test
    @DisplayName("英文连字符：被视为分隔符")
    void tokenize_hyphen() {
        List<String> tokens = tokenizer.tokenize("todo-service");
        assertThat(tokens).containsExactly("todo", "service");
    }

    @Test
    @DisplayName("数字：作为英文一起处理")
    void tokenize_digits() {
        List<String> tokens = tokenizer.tokenize("HTTP 200");
        assertThat(tokens).containsExactly("http", "200");
    }

    @Test
    @DisplayName("大小写统一：英文统一转小写")
    void tokenize_caseInsensitive() {
        assertThat(tokenizer.tokenize("JVM HEAP"))
                .containsExactly("jvm", "heap");
    }

    // ==================== 混合 ====================

    @Test
    @DisplayName("中英混合：CPU 使用率")
    void tokenize_mixed() {
        List<String> tokens = tokenizer.tokenize("CPU 使用率");
        assertThat(tokens).containsExactly(
                "cpu",
                "使", "使用",
                "用", "用率",
                "率"
        );
    }

    @Test
    @DisplayName("标点符号：作为分隔符")
    void tokenize_punctuation() {
        // "你好，世界！" 按单字 + 二元组推演：
        // 你, 你好, 好, 世, 世界, 界
        List<String> tokens = tokenizer.tokenize("你好，世界！");
        assertThat(tokens).containsExactly(
                "你", "你好", "好",
                "世", "世界", "界"
        );
    }

    // ==================== 边界值 ====================

    @Test
    @DisplayName("null 输入：返回空列表")
    void tokenize_null() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
    }

    @Test
    @DisplayName("空串 / 空白：返回空列表")
    void tokenize_blank() {
        assertThat(tokenizer.tokenize("")).isEmpty();
        assertThat(tokenizer.tokenize("   ")).isEmpty();
        assertThat(tokenizer.tokenize("\t\n")).isEmpty();
    }

    @Test
    @DisplayName("纯标点：返回空列表")
    void tokenize_purePunctuation() {
        assertThat(tokenizer.tokenize("!@#$%^&*()")).isEmpty();
    }
}