package com.example.opsaiagent.retrieval.bm25;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文分词器
 * 策略：
 * - 中文：单字 + 相邻二字组合
 * - 英文/数字：按字符边界切分
 * - 标点/空白：分隔符
 * 举例：
 * "内存泄漏" → [内, 内存, 存, 存泄, 泄, 泄漏, 漏]
 * "CPU 使用率" → [cpu, 使, 使用, 用, 用率, 率]
 */
public class ChineseTokenizer {

    /**
     * 分词
     * @param text 文本
     * @return 分词结果
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        char[] chars = text.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (isChinese(c)) {
                flushBuffer(buffer, tokens);
                // 单字
                tokens.add(String.valueOf(c));
                // 相邻二字组合
                if (i + 1 < chars.length && isChinese(chars[i + 1])) {
                    tokens.add("" + c + chars[i + 1]);
                }
            } else if (isEnglishOrDigit(c)) {
                buffer.append(Character.toLowerCase(c));
            } else {
                flushBuffer(buffer, tokens);
            }
        }
        flushBuffer(buffer, tokens);

        return tokens;
    }

    /**
     * 判断是否是中文字符
     * @param c 字符
     * @return 是否中文字符
     */
    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * 判断是否是英文字符或数字
     * @param c 字符
     * @return 是否英文字符或数字
     */
    private boolean isEnglishOrDigit(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
    }

    /**
     * 刷新缓冲区
     * @param buffer 缓冲区
     * @param tokens 分词结果
     */
    private void flushBuffer(StringBuilder buffer, List<String> tokens) {
        if (!buffer.isEmpty()) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }
}
