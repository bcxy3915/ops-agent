package com.example.opsaiagent.knowledge.ingest.splitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;

/**
 * 递归边界切分策略（主力，order=20）。
 *
 * 分隔符阶梯：段落（\n\n）→ 行（\n）→ 句终（。！？；.!?）→ 硬切分。
 * 注意：冒号不作为切分点（"标签：值"结构在冒号处切开是灾难）。
 *
 * 相邻块携带 overlapTokens 重叠（整句，从上一块尾部取），
 * 尾块不足 minChunkChars 时并入前一块。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecursiveBoundarySplitter implements TextChunkStrategy {

    public static final String NAME = "recursive";

    /** 句终与分句符号（中英文） */
    private static final char[] SENTENCE_ENDERS = {'。', '！', '？', '；', '.', '!', '?'};

    private final TokenMeter tokenMeter;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(SplitContext context) {
        String docType = context.docType();
        return "TXT".equals(docType) || "DOCX".equals(docType) || "PDF".equals(docType);
    }

    @Override
    public List<Document> split(List<Document> docs, SplitOptions options) {
        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String block : splitText(text, options)) {
                result.add(new Document(block, new HashMap<>(doc.getMetadata())));
            }
        }
        return result;
    }

    /**
     * 纯文本切分（可测试入口）：叶子单元 → 预算累积 → 尾块合并 → 重叠前缀。
     */
    List<String> splitText(String text, SplitOptions options) {
        List<String> units = leafUnits(text, options);
        List<String> blocks = accumulate(units, options);
        mergeTinyTail(blocks, options);
        applyOverlap(blocks, options);
        return blocks;
    }

    // ==================== 叶子单元生成 ====================

    /**
     * 生成叶子单元列表，拼接后与原文完全一致：
     * - 围栏代码块（```）与表格行（|...| 连续行）为原子单元（preserveCodeBlock 时）；
     * - 普通文本按句终符号切成句子级单元，空白保留在下一单元头部；
     * - 超过 maxChunkTokens 的病态长句按字符比例硬切。
     */
    private List<String> leafUnits(String text, SplitOptions options) {
        List<String> units = new ArrayList<>();
        List<String> normalBuffer = new ArrayList<>();
        List<String> lines = splitKeepBreaks(text);
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (options.preserveCodeBlock() && (stripped.startsWith("```") || stripped.startsWith("~~~"))) {
                List<String> fence = new ArrayList<>();
                fence.add(lines.get(i));
                i++;
                while (i < lines.size()) {
                    fence.add(lines.get(i));
                    i++;
                    // 闭围栏行（``` / ~~~ 开头）结束代码块
                    if (fence.get(fence.size() - 1).strip().startsWith(stripped.substring(0, 3))) {
                        break;
                    }
                }
                flushNormal(normalBuffer, units, options);
                addProtectedUnit(units, String.join("", fence), options);
                continue;
            }
            if (options.preserveCodeBlock() && isTableLine(stripped)) {
                List<String> table = new ArrayList<>();
                while (i < lines.size() && isTableLine(lines.get(i).strip())) {
                    table.add(lines.get(i));
                    i++;
                }
                flushNormal(normalBuffer, units, options);
                addProtectedUnit(units, String.join("", table), options);
                continue;
            }
            normalBuffer.add(line);
            i++;
        }
        flushNormal(normalBuffer, units, options);
        return units;
    }

    /** 原子单元超过硬上限时按行退让切分（保留行完整性，运维命令/表格行不切半） */
    private void addProtectedUnit(List<String> units, String unit, SplitOptions options) {
        int tokens = tokenMeter.count(unit);
        if (tokens <= options.maxChunkTokens()) {
            units.add(unit);
            return;
        }
        List<String> lines = splitKeepBreaks(unit);
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        for (String line : lines) {
            int lineTokens = tokenMeter.count(line);
            if (currentTokens > 0 && currentTokens + lineTokens > options.chunkSizeTokens()) {
                units.add(current.toString());
                current.setLength(0);
                currentTokens = 0;
            }
            current.append(line);
            currentTokens += lineTokens;
        }
        if (!current.isEmpty()) {
            units.add(current.toString());
        }
    }

    /** 普通文本缓冲 → 句子级单元；病态长句硬切 */
    private void flushNormal(List<String> buffer, List<String> units, SplitOptions options) {
        if (buffer.isEmpty()) {
            return;
        }
        String block = String.join("", buffer);
        buffer.clear();
        for (String sentence : splitSentences(block)) {
            int tokens = tokenMeter.count(sentence);
            if (tokens > options.maxChunkTokens()) {
                units.addAll(tokenMeter.hardSplitByTokens(sentence, tokens, options.chunkSizeTokens()));
            } else {
                units.add(sentence);
            }
        }
    }

    /**
     * 句子切分：在句终符号之后、空白之前断开；空白保留在下一句头部，保证拼接无损。
     */
    static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSentenceEnder(c)) {
                boolean isChineseEnder = "。！？；".indexOf(c) >= 0;
                // 第二道防线：中文标点直接过；英文标点要求后面有空格
                if (isChineseEnder || i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))) {
                    sentences.add(text.substring(start, i + 1));
                    start = i + 1;
                }
            }
        }
        if (start < text.length()) {
            sentences.add(text.substring(start));
        }
        return sentences;
    }

    /** 按行切分并保留行尾换行符（拼接无损） */
    static List<String> splitKeepBreaks(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf('\n', start)) >= 0) {
            lines.add(text.substring(start, idx + 1));
            start = idx + 1;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static boolean isTableLine(String strippedLine) {
        return strippedLine.startsWith("|") && strippedLine.endsWith("|") && strippedLine.length() > 2;
    }

    private static boolean isSentenceEnder(char c) {
        for (char ender : SENTENCE_ENDERS) {
            if (c == ender) {
                return true;
            }
        }
        return false;
    }

    // ==================== 累积与后处理 ====================

    /** 贪心累积：达到 chunkSizeTokens 即封块；单个超预算单元独立成块（不超 maxChunkTokens） */
    private List<String> accumulate(List<String> units, SplitOptions options) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        for (String unit : units) {
            int unitTokens = tokenMeter.count(unit);
            if (currentTokens > 0 && currentTokens + unitTokens > options.chunkSizeTokens()) {
                blocks.add(current.toString());
                current.setLength(0);
                currentTokens = 0;
            }
            current.append(unit);
            currentTokens += unitTokens;
        }
        if (!current.toString().isBlank()) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    /** 尾块过短时并入前一块（合并后不得超过 maxChunkTokens） */
    private void mergeTinyTail(List<String> blocks, SplitOptions options) {
        if (blocks.size() < 2) {
            return;
        }
        int last = blocks.size() - 1;
        String tail = blocks.get(last);
        if (tail.strip().length() >= options.minChunkChars()) {
            return;
        }
        String prev = blocks.get(last - 1);
        if (tokenMeter.count(prev) + tokenMeter.count(tail) > options.maxChunkTokens()) {
            return;
        }
        blocks.set(last - 1, prev + tail);
        blocks.remove(last);
    }

    /** 相邻块重叠：从上一块尾部取整句，累计不超过 overlapTokens；整块重复无意义则跳过 */
    private void applyOverlap(List<String> blocks, SplitOptions options) {
        if (options.overlapTokens() <= 0) {
            return;
        }
        for (int i = 1; i < blocks.size(); i++) {
            List<String> sentences = splitSentences(blocks.get(i - 1));
            Deque<String> picked = new ArrayDeque<>();
            int tokens = 0;
            for (int j = sentences.size() - 1; j >= 0; j--) {
                String sentence = sentences.get(j);
                if (sentence.isBlank()) {
                    continue;
                }
                int sentenceTokens = tokenMeter.count(sentence);
                if (tokens + sentenceTokens > options.overlapTokens()) {
                    break;
                }
                picked.addFirst(sentence);
                tokens += sentenceTokens;
            }
            while (!picked.isEmpty()) {
                String prefix = String.join("", picked);
                if (prefix.length() >= blocks.get(i - 1).length()) {
                    break;
                }
                if (tokenMeter.count(prefix) + tokenMeter.count(blocks.get(i)) <= options.maxChunkTokens()) {
                    blocks.set(i, prefix.stripLeading() + blocks.get(i));
                    break;
                }
                picked.pollFirst();
            }
        }
    }
}
